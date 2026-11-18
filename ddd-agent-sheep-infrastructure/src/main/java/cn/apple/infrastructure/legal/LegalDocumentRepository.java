package cn.apple.infrastructure.legal;

import cn.apple.domain.legal.adapter.repository.ILegalDocumentRepository;
import cn.apple.domain.legal.model.entity.LegalDocument;
import cn.apple.domain.legal.model.entity.LegalDocumentIngestionStats;
import cn.apple.domain.legal.model.valobj.LegalDocumentChunk;
import cn.apple.domain.legal.model.valobj.LegalDocumentSource;
import cn.apple.domain.legal.model.valobj.LegalDocumentScope;
import cn.apple.domain.legal.model.valobj.LegalCorpusType;
import cn.apple.domain.legal.model.valobj.LegalKnowledgeMetadata;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Properties;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Repository
@ConditionalOnProperty(name = "app.infrastructure.enabled", havingValue = "true")
public class LegalDocumentRepository implements ILegalDocumentRepository {

    private static final long DEFAULT_MAX_SOURCE_BYTES = 200L * 1024 * 1024;
    private static final long INGESTION_ADVISORY_LOCK_ID = 5495877261102301779L;
    private static final int MAX_INGESTION_BATCH_SIZE = 512;

    private final VectorStore vectorStore;
    private final JdbcTemplate pgVectorJdbcTemplate;
    private final int ingestionBatchSize;
    private final String vectorTable;
    private final Path legalRoot;
    private final Path rawDir;
    private final Path manifestDir;
    private final long maxSourceBytes;

    @Autowired
    public LegalDocumentRepository(@Qualifier("vectorStore") VectorStore vectorStore,
                                   @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                                   @Value("${app.legal.knowledge.ingestion-batch-size:64}") int ingestionBatchSize,
                                   @Value("${app.legal.knowledge.vector-table:"
                                           + LegalKnowledgeMetadata.DEFAULT_VECTOR_TABLE + "}") String vectorTable,
                                   @Value("${app.legal.knowledge.storage-root:data/legal}") String storageRoot,
                                   @Value("${app.legal.knowledge.max-source-bytes:209715200}") long maxSourceBytes) {
        this.vectorStore = vectorStore;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.ingestionBatchSize = Math.max(1, Math.min(MAX_INGESTION_BATCH_SIZE, ingestionBatchSize));
        this.vectorTable = validateTableName(vectorTable);
        this.legalRoot = validateStorageRoot(storageRoot);
        this.rawDir = legalRoot.resolve("raw");
        this.manifestDir = legalRoot.resolve("manifest");
        this.maxSourceBytes = maxSourceBytes <= 0 ? DEFAULT_MAX_SOURCE_BYTES : maxSourceBytes;
    }

    LegalDocumentRepository(VectorStore vectorStore,
                            JdbcTemplate pgVectorJdbcTemplate,
                            int ingestionBatchSize,
                            String vectorTable,
                            String storageRoot) {
        this(vectorStore, pgVectorJdbcTemplate, ingestionBatchSize, vectorTable, storageRoot,
                DEFAULT_MAX_SOURCE_BYTES);
    }

    @Override
    public void ensureWorkspace() {
        try {
            Files.createDirectories(rawDir);
            Files.createDirectories(legalRoot.resolve("processed"));
            Files.createDirectories(legalRoot.resolve("eval"));
            Files.createDirectories(manifestDir);
        } catch (IOException e) {
            throw new IllegalStateException("创建法律文档目录失败", e);
        }
    }

    @Override
    public List<LegalDocumentSource> listSources() {
        try (Stream<Path> stream = Files.walk(rawDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(file -> !Files.isSymbolicLink(file))
                    .filter(this::isSupportedFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .map(this::sourceFromFile)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("读取法律文档目录失败: " + rawDir.toAbsolutePath(), e);
        }
    }

    @Override
    public LegalDocument read(LegalDocumentSource source) {
        if (!withinSizeLimit(source.sourcePath())) {
            throw new IllegalArgumentException("legal source exceeds configured size limit or cannot be inspected: "
                    + source.sourcePath().getFileName());
        }
        String text = readText(source.sourcePath());
        String lawName = source.scope().corpusType() == LegalCorpusType.PUBLIC_LAW
                ? resolveLawName(source.docName(), text)
                : "";
        return new LegalDocument(source, lawName, text);
    }

    @Override
    public LegalDocumentSource stageSource(String documentId,
                                           String originalName,
                                           LegalDocumentScope scope,
                                           String objectUri,
                                           InputStream content) {
        ensureWorkspace();
        String checkedDocumentId = validateDocumentId(documentId);
        String extension = supportedExtension(originalName);
        LegalDocumentScope checkedScope = scope == null ? LegalDocumentScope.publicLaw() : scope;
        Path directory = rawDir.resolve(scopeDirectory(checkedScope)).normalize();
        Path target = directory.resolve(checkedDocumentId + extension).normalize();
        if (!target.startsWith(rawDir.toAbsolutePath().normalize()) && rawDir.isAbsolute()) {
            throw new IllegalArgumentException("legal staging path escapes storage root");
        }
        Path metadata = metadataPath(target);
        Path sourceBackup = target.resolveSibling(target.getFileName() + ".bak-" + UUID.randomUUID());
        Path metadataBackup = metadata.resolveSibling(metadata.getFileName() + ".bak-" + UUID.randomUUID());
        boolean hadSource = Files.exists(target);
        boolean hadMetadata = Files.exists(metadata);
        try {
            Files.createDirectories(directory);
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
            try {
                Files.copy(content, temporary, StandardCopyOption.REPLACE_EXISTING);
                if (Files.exists(target)) moveReplacing(target, sourceBackup);
                if (Files.exists(metadata)) moveReplacing(metadata, metadataBackup);
                moveReplacing(temporary, target);
                writeSourceMetadata(metadata, checkedDocumentId, originalName, checkedScope, objectUri);
            } finally {
                Files.deleteIfExists(temporary);
            }
            deleteBackupQuietly(sourceBackup);
            deleteBackupQuietly(metadataBackup);
            return new LegalDocumentSource(checkedDocumentId, originalName, target, checkedScope, objectUri);
        } catch (IOException | RuntimeException error) {
            try {
                restoreStagingBackup(
                        target, metadata, sourceBackup, metadataBackup, hadSource, hadMetadata);
            } catch (IOException restoreError) {
                error.addSuppressed(restoreError);
            }
            if (error instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("failed to stage legal document for ingestion", error);
        }
    }

    @Override
    public void saveChunks(List<LegalDocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        Set<String> documentIds = documentIds(chunks);
        synchronizeChunks(chunks, null, documentIds);
    }

    @Override
    public void replaceChunks(List<LegalDocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        Set<String> documentIds = documentIds(chunks);
        synchronizeChunks(chunks, null, documentIds);
    }

    @Override
    public void synchronizeChunks(List<LegalDocumentChunk> chunks,
                                  Set<String> sourceDocumentIds,
                                  Set<String> processedDocumentIds) {
        List<LegalDocumentChunk> safeChunks = chunks == null ? List.of() : List.copyOf(chunks);
        Set<String> safeSourceIds = sourceDocumentIds == null ? null : normalizedIds(sourceDocumentIds);
        Set<String> safeProcessedIds = normalizedIds(processedDocumentIds);
        Map<String, DocumentVersion> documents = groupAndValidate(safeChunks, safeSourceIds, safeProcessedIds);

        pgVectorJdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            if (!tryAcquireIngestionLock(connection)) {
                throw new IllegalStateException("legal knowledge ingestion is already running on another instance");
            }
            try {
                synchronizeLocked(connection, documents, safeSourceIds, safeProcessedIds);
                return null;
            } finally {
                releaseIngestionLock(connection);
            }
        });
    }

    @Override
    public void saveManifest(LegalDocumentIngestionStats stats) {
        Path manifest = manifestDir.resolve("law-documents-manifest.json");
        try {
            Files.writeString(manifest, ManifestJsonWriter.toJson(stats), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入法律文档清单失败: " + manifest, e);
        }
    }

    private void synchronizeLocked(Connection connection,
                                   Map<String, DocumentVersion> documents,
                                   Set<String> sourceDocumentIds,
                                   Set<String> processedDocumentIds) throws SQLException {
        adoptLegacyFilesystemDocuments(connection);
        cleanupStaging(connection);
        List<DocumentVersion> changedDocuments = new ArrayList<>();
        for (DocumentVersion document : documents.values()) {
            verifyCatalogScope(connection, document);
            if (!isCompleteActiveVersion(connection, document)) {
                changedDocuments.add(document);
            }
        }

        String runId = UUID.randomUUID().toString();
        String ingestedAt = Instant.now().toString();
        try {
            for (DocumentVersion document : changedDocuments) {
                stageDocument(document, runId, ingestedAt);
                verifyStagedVersion(connection, document, runId);
            }
            activateAndCleanup(connection, documents, changedDocuments, sourceDocumentIds, processedDocumentIds, runId);
        } catch (Exception error) {
            try {
                cleanupStaging(connection);
            } catch (Exception cleanupError) {
                error.addSuppressed(cleanupError);
            }
            if (error instanceof SQLException sqlException) {
                throw sqlException;
            }
            if (error instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("legal knowledge synchronization failed", error);
        }
    }

    private void stageDocument(DocumentVersion document, String runId, String ingestedAt) {
        List<LegalDocumentChunk> chunks = document.chunks();
        for (int start = 0; start < chunks.size(); start += ingestionBatchSize) {
            int end = Math.min(chunks.size(), start + ingestionBatchSize);
            List<Document> batch = chunks.subList(start, end).stream()
                    .map(chunk -> toDocument(chunk, chunks.size(), LegalKnowledgeMetadata.STATUS_STAGING, runId, ingestedAt))
                    .toList();
            vectorStore.accept(batch);
        }
    }

    private void activateAndCleanup(Connection connection,
                                    Map<String, DocumentVersion> documents,
                                    List<DocumentVersion> changedDocuments,
                                    Set<String> sourceDocumentIds,
                                    Set<String> processedDocumentIds,
                                    String runId) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (DocumentVersion document : changedDocuments) {
                int activated = executeUpdate(connection, """
                        update %s
                           set metadata = jsonb_set(metadata, '{%s}', to_jsonb(cast(? as text)), true)
                         where metadata ->> '%s' = ?
                           and metadata ->> '%s' = ?
                           and metadata ->> '%s' = ?
                           and metadata ->> '%s' = ?
                           and metadata ->> '%s' = ?
                           and metadata ->> '%s' = ?
                        """.formatted(
                                vectorTable,
                                LegalKnowledgeMetadata.INGESTION_STATUS,
                                LegalKnowledgeMetadata.KNOWLEDGE,
                                LegalKnowledgeMetadata.INGESTION_SOURCE,
                                LegalKnowledgeMetadata.INGESTION_STATUS,
                                LegalKnowledgeMetadata.DOC_ID,
                                LegalKnowledgeMetadata.DOCUMENT_VERSION,
                                LegalKnowledgeMetadata.INGESTION_RUN_ID),
                        LegalKnowledgeMetadata.STATUS_ACTIVE,
                        LegalKnowledgeMetadata.DEFAULT_KNOWLEDGE_TAG,
                        LegalKnowledgeMetadata.SOURCE_LEGAL_FILESYSTEM,
                        LegalKnowledgeMetadata.STATUS_STAGING,
                        document.docId(), document.documentVersion(), runId);
                if (activated != document.chunks().size()) {
                    throw new IllegalStateException("staged legal document activation count mismatch: " + document.docId());
                }
            }

            for (DocumentVersion document : documents.values()) {
                executeUpdate(connection, """
                        delete from %s
                         where metadata ->> '%s' = ?
                           and metadata ->> '%s' = ?
                           and metadata ->> '%s' = ?
                           and coalesce(metadata ->> '%s', '') <> ?
                        """.formatted(
                                vectorTable,
                                LegalKnowledgeMetadata.KNOWLEDGE,
                                LegalKnowledgeMetadata.INGESTION_SOURCE,
                                LegalKnowledgeMetadata.DOC_ID,
                                LegalKnowledgeMetadata.DOCUMENT_VERSION),
                        LegalKnowledgeMetadata.DEFAULT_KNOWLEDGE_TAG,
                        LegalKnowledgeMetadata.SOURCE_LEGAL_FILESYSTEM,
                        document.docId(), document.documentVersion());
            }

            Set<String> emptyProcessedDocuments = new LinkedHashSet<>(processedDocumentIds);
            emptyProcessedDocuments.removeAll(documents.keySet());
            for (String docId : emptyProcessedDocuments) {
                deleteDocument(connection, docId);
            }

            if (sourceDocumentIds != null) {
                for (String storedDocId : storedDocumentIds(connection)) {
                    if (!sourceDocumentIds.contains(storedDocId)) {
                        deleteDocument(connection, storedDocId);
                    }
                }
            }
            for (DocumentVersion document : documents.values()) {
                markCatalogVersionActive(connection, document);
            }
            cleanupStaging(connection);
            connection.commit();
        } catch (Exception error) {
            connection.rollback();
            if (error instanceof SQLException sqlException) {
                throw sqlException;
            }
            if (error instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("failed to activate legal knowledge version", error);
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private boolean isCompleteActiveVersion(Connection connection, DocumentVersion document) throws SQLException {
        String sql = """
                select count(*) as chunk_count,
                       count(distinct metadata ->> '%s') as distinct_chunk_count,
                       min(cast(metadata ->> '%s' as integer)) as min_chunk_index,
                       max(cast(metadata ->> '%s' as integer)) as max_chunk_index
                  from %s
                 where metadata ->> '%s' = ?
                   and metadata ->> '%s' = ?
                   and metadata ->> '%s' = ?
                   and metadata ->> '%s' = ?
                   and metadata ->> '%s' = ?
                   and metadata ->> '%s' = ?
                   and metadata ->> '%s' = ?
                """.formatted(
                        LegalKnowledgeMetadata.CHUNK_INDEX,
                        LegalKnowledgeMetadata.CHUNK_INDEX,
                        LegalKnowledgeMetadata.CHUNK_INDEX,
                        vectorTable,
                        LegalKnowledgeMetadata.KNOWLEDGE,
                        LegalKnowledgeMetadata.INGESTION_SOURCE,
                        LegalKnowledgeMetadata.INGESTION_STATUS,
                        LegalKnowledgeMetadata.DOC_ID,
                        LegalKnowledgeMetadata.DOCUMENT_VERSION,
                        LegalKnowledgeMetadata.CHUNK_SCHEMA_VERSION,
                        LegalKnowledgeMetadata.CHUNK_COUNT);
        try (PreparedStatement statement = prepare(connection, sql,
                LegalKnowledgeMetadata.DEFAULT_KNOWLEDGE_TAG,
                LegalKnowledgeMetadata.SOURCE_LEGAL_FILESYSTEM,
                LegalKnowledgeMetadata.STATUS_ACTIVE,
                document.docId(), document.documentVersion(), document.chunkSchemaVersion(),
                String.valueOf(document.chunks().size()));
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next()
                    && resultSet.getLong("chunk_count") == document.chunks().size()
                    && resultSet.getLong("distinct_chunk_count") == document.chunks().size()
                    && resultSet.getInt("min_chunk_index") == 0
                    && resultSet.getInt("max_chunk_index") == document.chunks().size() - 1;
        }
    }

    private void verifyStagedVersion(Connection connection, DocumentVersion document, String runId) throws SQLException {
        String sql = """
                select count(*) as chunk_count,
                       count(distinct metadata ->> '%s') as distinct_chunk_count,
                       min(cast(metadata ->> '%s' as integer)) as min_chunk_index,
                       max(cast(metadata ->> '%s' as integer)) as max_chunk_index
                  from %s
                 where metadata ->> '%s' = ?
                   and metadata ->> '%s' = ?
                   and metadata ->> '%s' = ?
                   and metadata ->> '%s' = ?
                   and metadata ->> '%s' = ?
                   and metadata ->> '%s' = ?
                   and metadata ->> '%s' = ?
                   and metadata ->> '%s' = ?
                """.formatted(
                        LegalKnowledgeMetadata.CHUNK_INDEX,
                        LegalKnowledgeMetadata.CHUNK_INDEX,
                        LegalKnowledgeMetadata.CHUNK_INDEX,
                        vectorTable,
                        LegalKnowledgeMetadata.KNOWLEDGE,
                        LegalKnowledgeMetadata.INGESTION_SOURCE,
                        LegalKnowledgeMetadata.INGESTION_STATUS,
                        LegalKnowledgeMetadata.DOC_ID,
                        LegalKnowledgeMetadata.DOCUMENT_VERSION,
                        LegalKnowledgeMetadata.INGESTION_RUN_ID,
                        LegalKnowledgeMetadata.CHUNK_SCHEMA_VERSION,
                        LegalKnowledgeMetadata.CHUNK_COUNT);
        try (PreparedStatement statement = prepare(connection, sql,
                LegalKnowledgeMetadata.DEFAULT_KNOWLEDGE_TAG,
                LegalKnowledgeMetadata.SOURCE_LEGAL_FILESYSTEM,
                LegalKnowledgeMetadata.STATUS_STAGING,
                document.docId(), document.documentVersion(), runId, document.chunkSchemaVersion(),
                String.valueOf(document.chunks().size()));
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()
                    || resultSet.getLong("chunk_count") != document.chunks().size()
                    || resultSet.getLong("distinct_chunk_count") != document.chunks().size()
                    || resultSet.getInt("min_chunk_index") != 0
                    || resultSet.getInt("max_chunk_index") != document.chunks().size() - 1) {
                throw new IllegalStateException("staged legal document is incomplete: " + document.docId());
            }
        }
    }

    private void adoptLegacyFilesystemDocuments(Connection connection) throws SQLException {
        executeUpdate(connection, """
                update %s
                   set metadata = jsonb_set(metadata, '{%s}', to_jsonb(cast(? as text)), true)
                 where metadata ->> '%s' = ?
                   and metadata ->> '%s' is null
                   and metadata ->> '%s' is not null
                   and metadata ->> '%s' is not null
                   and metadata ->> '%s' is not null
                   and metadata ->> '%s' is not null
                   and metadata ->> '%s' like ?
                """.formatted(
                        vectorTable,
                        LegalKnowledgeMetadata.INGESTION_SOURCE,
                        LegalKnowledgeMetadata.KNOWLEDGE,
                        LegalKnowledgeMetadata.INGESTION_SOURCE,
                        LegalKnowledgeMetadata.SOURCE_PATH,
                        LegalKnowledgeMetadata.SOURCE_CHECKSUM,
                        LegalKnowledgeMetadata.DOCUMENT_VERSION,
                        LegalKnowledgeMetadata.CHUNK_INDEX,
                        LegalKnowledgeMetadata.SOURCE_PATH),
                LegalKnowledgeMetadata.SOURCE_LEGAL_FILESYSTEM,
                LegalKnowledgeMetadata.DEFAULT_KNOWLEDGE_TAG,
                sourcePathPattern());
    }

    private void cleanupStaging(Connection connection) throws SQLException {
        executeUpdate(connection, """
                delete from %s
                 where metadata ->> '%s' = ?
                   and metadata ->> '%s' = ?
                   and metadata ->> '%s' = ?
                """.formatted(
                        vectorTable,
                        LegalKnowledgeMetadata.KNOWLEDGE,
                        LegalKnowledgeMetadata.INGESTION_SOURCE,
                        LegalKnowledgeMetadata.INGESTION_STATUS),
                LegalKnowledgeMetadata.DEFAULT_KNOWLEDGE_TAG,
                LegalKnowledgeMetadata.SOURCE_LEGAL_FILESYSTEM,
                LegalKnowledgeMetadata.STATUS_STAGING);
    }

    private Set<String> storedDocumentIds(Connection connection) throws SQLException {
        Set<String> result = new LinkedHashSet<>();
        String sql = """
                select distinct metadata ->> '%s' as doc_id
                  from %s
                 where metadata ->> '%s' = ?
                   and metadata ->> '%s' = ?
                   and metadata ->> '%s' is not null
                """.formatted(
                        LegalKnowledgeMetadata.DOC_ID,
                        vectorTable,
                        LegalKnowledgeMetadata.KNOWLEDGE,
                        LegalKnowledgeMetadata.INGESTION_SOURCE,
                        LegalKnowledgeMetadata.DOC_ID);
        try (PreparedStatement statement = prepare(connection, sql,
                LegalKnowledgeMetadata.DEFAULT_KNOWLEDGE_TAG,
                LegalKnowledgeMetadata.SOURCE_LEGAL_FILESYSTEM);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String docId = resultSet.getString("doc_id");
                if (docId != null && !docId.isBlank()) {
                    result.add(docId);
                }
            }
        }
        return result;
    }

    private void deleteDocument(Connection connection, String docId) throws SQLException {
        executeUpdate(connection, """
                delete from %s
                 where metadata ->> '%s' = ?
                   and metadata ->> '%s' = ?
                   and metadata ->> '%s' = ?
                """.formatted(
                        vectorTable,
                        LegalKnowledgeMetadata.KNOWLEDGE,
                        LegalKnowledgeMetadata.INGESTION_SOURCE,
                        LegalKnowledgeMetadata.DOC_ID),
                LegalKnowledgeMetadata.DEFAULT_KNOWLEDGE_TAG,
                LegalKnowledgeMetadata.SOURCE_LEGAL_FILESYSTEM,
                docId);
    }

    private void markCatalogVersionActive(Connection connection,
                                          DocumentVersion document) throws SQLException {
        LegalDocumentChunk first = document.chunks().get(0);
        if (first.objectUri() == null || first.objectUri().isBlank()) {
            return;
        }
        executeUpdate(connection, """
                update legal_document_version
                   set ingestion_status = 'SUPERSEDED'
                 where document_id = ?
                   and object_uri <> ?
                   and ingestion_status = 'ACTIVE'
                """, document.docId(), first.objectUri());
        executeUpdate(connection, """
                update legal_document_version
                   set ingestion_status = 'ACTIVE',
                       chunk_schema_version = ?,
                       chunk_count = ?,
                       ingested_at = now()
                 where document_id = ?
                   and object_uri = ?
                """, document.chunkSchemaVersion(), document.chunks().size(),
                document.docId(), first.objectUri());
    }

    private void verifyCatalogScope(Connection connection, DocumentVersion document) throws SQLException {
        LegalDocumentChunk first = document.chunks().get(0);
        String sql = """
                select corpus_type, tenant_id, matter_id, owner_subject_id,
                       exists(
                           select 1 from legal_document_version v
                            where v.document_id = d.document_id
                              and v.object_uri = ?
                       ) as object_version_known
                  from legal_document d
                 where d.document_id = ?
                """;
        try (PreparedStatement statement = prepare(connection, sql, first.objectUri(), document.docId());
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return;
            }
            LegalDocumentScope scope = first.scope();
            boolean matches = scope.corpusType().code().equals(resultSet.getString("corpus_type"))
                    && Objects.equals(scope.tenantId(), resultSet.getString("tenant_id"))
                    && Objects.equals(scope.matterId(), resultSet.getString("matter_id"))
                    && Objects.equals(scope.accessSubjectId(), resultSet.getString("owner_subject_id"));
            if (!matches || first.objectUri() == null || !resultSet.getBoolean("object_version_known")) {
                throw new SecurityException("legal source scope or object version conflicts with catalog: "
                        + document.docId());
            }
        }
    }

    private boolean tryAcquireIngestionLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = prepare(connection, "select pg_try_advisory_lock(?)", INGESTION_ADVISORY_LOCK_ID);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() && resultSet.getBoolean(1);
        }
    }

    private void releaseIngestionLock(Connection connection) {
        try (PreparedStatement statement = prepare(connection, "select pg_advisory_unlock(?)", INGESTION_ADVISORY_LOCK_ID)) {
            statement.execute();
        } catch (SQLException ignored) {
            // Closing the connection also releases a session-level advisory lock.
        }
    }

    private int executeUpdate(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters)) {
            return statement.executeUpdate();
        }
    }

    private PreparedStatement prepare(Connection connection, String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement;
        } catch (SQLException | RuntimeException error) {
            statement.close();
            throw error;
        }
    }

    private Map<String, DocumentVersion> groupAndValidate(List<LegalDocumentChunk> chunks,
                                                          Set<String> sourceDocumentIds,
                                                          Set<String> processedDocumentIds) {
        Map<String, List<LegalDocumentChunk>> grouped = new LinkedHashMap<>();
        for (LegalDocumentChunk chunk : chunks) {
            if (chunk == null || chunk.docId() == null || chunk.docId().isBlank()) {
                throw new IllegalArgumentException("legal chunk docId must not be blank");
            }
            grouped.computeIfAbsent(chunk.docId(), ignored -> new ArrayList<>()).add(chunk);
        }
        if (!processedDocumentIds.containsAll(grouped.keySet())) {
            throw new IllegalArgumentException("chunk documents must be included in processedDocumentIds");
        }
        if (sourceDocumentIds != null && !sourceDocumentIds.containsAll(processedDocumentIds)) {
            throw new IllegalArgumentException("processedDocumentIds must be included in sourceDocumentIds");
        }

        Map<String, DocumentVersion> result = new LinkedHashMap<>();
        grouped.forEach((docId, documentChunks) -> {
            Set<String> versions = new LinkedHashSet<>();
            Set<String> schemaVersions = new LinkedHashSet<>();
            Set<Integer> indexes = new LinkedHashSet<>();
            LegalDocumentScope expectedScope = documentChunks.get(0).scope();
            String expectedObjectUri = documentChunks.get(0).objectUri();
            for (LegalDocumentChunk chunk : documentChunks) {
                if (chunk.documentVersion() == null || chunk.documentVersion().isBlank()) {
                    throw new IllegalArgumentException("legal chunk documentVersion must not be blank: " + docId);
                }
                versions.add(chunk.documentVersion());
                if (chunk.chunkSchemaVersion() == null || chunk.chunkSchemaVersion().isBlank()) {
                    throw new IllegalArgumentException("legal chunk schema version must not be blank: " + docId);
                }
                schemaVersions.add(chunk.chunkSchemaVersion());
                if (!indexes.add(chunk.chunkIndex())) {
                    throw new IllegalArgumentException("duplicate legal chunk index: " + docId + "/" + chunk.chunkIndex());
                }
                if (!expectedScope.equals(chunk.scope())
                        || !Objects.equals(expectedObjectUri, chunk.objectUri())) {
                    throw new IllegalArgumentException("legal document chunks must share one access scope and object: "
                            + docId);
                }
            }
            if (versions.size() != 1) {
                throw new IllegalArgumentException("a legal document sync must contain exactly one version: " + docId);
            }
            if (schemaVersions.size() != 1) {
                throw new IllegalArgumentException("a legal document sync must contain exactly one chunk schema: " + docId);
            }
            for (int index = 0; index < documentChunks.size(); index++) {
                if (!indexes.contains(index)) {
                    throw new IllegalArgumentException("legal chunk indexes must be contiguous from zero: " + docId);
                }
            }
            result.put(docId, new DocumentVersion(
                    docId,
                    versions.iterator().next(),
                    schemaVersions.iterator().next(),
                    List.copyOf(documentChunks)));
        });
        return result;
    }

    private Set<String> documentIds(List<LegalDocumentChunk> chunks) {
        Set<String> result = new LinkedHashSet<>();
        for (LegalDocumentChunk chunk : chunks) {
            if (chunk != null && chunk.docId() != null && !chunk.docId().isBlank()) {
                result.add(chunk.docId());
            }
        }
        return result;
    }

    private Set<String> normalizedIds(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(result::add);
        }
        return Set.copyOf(result);
    }

    Document toDocument(LegalDocumentChunk chunk,
                        int chunkCount,
                        String ingestionStatus,
                        String runId,
                        String ingestedAt) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(LegalKnowledgeMetadata.KNOWLEDGE, LegalKnowledgeMetadata.DEFAULT_KNOWLEDGE_TAG);
        metadata.putAll(chunk.scope().metadata());
        metadata.put(LegalKnowledgeMetadata.DOC_ID, chunk.docId());
        metadata.put(LegalKnowledgeMetadata.DOC_NAME, chunk.docName());
        metadata.put(LegalKnowledgeMetadata.LAW_NAME, chunk.lawName());
        metadata.put(LegalKnowledgeMetadata.ARTICLE_NO, chunk.articleNo());
        metadata.put(LegalKnowledgeMetadata.CLAUSE_NO, chunk.clauseNo());
        metadata.put(LegalKnowledgeMetadata.CHUNK_INDEX, chunk.chunkIndex());
        metadata.put(LegalKnowledgeMetadata.CHUNK_COUNT, chunkCount);
        metadata.put(LegalKnowledgeMetadata.CHUNK_SCHEMA_VERSION, chunk.chunkSchemaVersion());
        metadata.put(LegalKnowledgeMetadata.SOURCE_PATH, chunk.sourcePath());
        if (chunk.objectUri() != null) {
            metadata.put(LegalKnowledgeMetadata.OBJECT_URI, chunk.objectUri());
        }
        metadata.put(LegalKnowledgeMetadata.SOURCE_CHECKSUM, chunk.sourceChecksum());
        metadata.put(LegalKnowledgeMetadata.DOCUMENT_VERSION, chunk.documentVersion());
        metadata.put(LegalKnowledgeMetadata.EFFECTIVE_DATE, chunk.effectiveDate());
        metadata.put(LegalKnowledgeMetadata.PARENT_REF, chunk.parentRef());
        metadata.put(LegalKnowledgeMetadata.SECTION_PATH, chunk.parentRef());
        metadata.put(LegalKnowledgeMetadata.INGESTION_STATUS, ingestionStatus);
        metadata.put(LegalKnowledgeMetadata.INGESTION_SOURCE, LegalKnowledgeMetadata.SOURCE_LEGAL_FILESYSTEM);
        metadata.put(LegalKnowledgeMetadata.INGESTION_RUN_ID, runId);
        metadata.put(LegalKnowledgeMetadata.INGESTED_AT, ingestedAt);
        String chunkId = UUID.nameUUIDFromBytes(
                (chunk.docId() + "|" + chunk.documentVersion() + "|" + chunk.chunkIndex())
                        .getBytes(StandardCharsets.UTF_8)).toString();
        return new Document(chunkId, chunk.text(), metadata);
    }

    private String validateTableName(String value) {
        String checked = value == null ? "" : value.trim();
        if (!checked.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("app.legal.knowledge.vector-table must be a simple lowercase SQL identifier");
        }
        return checked;
    }

    private Path validateStorageRoot(String value) {
        String checked = value == null ? "" : value.trim();
        if (checked.isBlank()) {
            throw new IllegalArgumentException("app.legal.knowledge.storage-root must not be blank");
        }
        return Path.of(checked).normalize();
    }

    private String sourcePathPattern() {
        String prefix = rawDir.toString().replace('\\', '/');
        String escaped = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return (escaped.endsWith("/") ? escaped : escaped + "/") + "%";
    }

    private String readText(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".txt") || fileName.endsWith(".md")) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("读取文本文件失败: " + file, e);
            }
        }
        TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(file));
        return reader.get().stream().map(Document::getText).reduce("", (left, right) -> left + System.lineSeparator() + right);
    }

    private String resolveLawName(String originalName, String text) {
        String firstLine = text.lines().map(String::trim).filter(line -> !line.isBlank()).findFirst().orElse("");
        Matcher bookTitle = Pattern.compile("《([^》]+)》").matcher(firstLine);
        if (bookTitle.find()) {
            return bookTitle.group(1);
        }
        return originalName.replaceFirst("\\.[^.]+$", "");
    }

    private boolean isSupportedFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".pdf")
                || name.endsWith(".doc") || name.endsWith(".docx");
    }

    private boolean withinSizeLimit(Path file) {
        try {
            return Files.size(file) <= maxSourceBytes;
        } catch (IOException e) {
            return false;
        }
    }

    private String stableDocId(Path file) {
        return rawDir.relativize(file).toString().replace('\\', '/');
    }

    private LegalDocumentSource sourceFromFile(Path file) {
        Properties metadata = readSourceMetadata(metadataPath(file));
        String docId = metadata.getProperty("documentId", stableDocId(file));
        String docName = metadata.getProperty("originalName", file.getFileName().toString());
        String objectUri = blankToNull(metadata.getProperty("objectUri"));
        LegalDocumentScope scope = metadata.containsKey("corpusType")
                ? new LegalDocumentScope(
                        LegalCorpusType.fromCode(metadata.getProperty("corpusType")),
                        blankToNull(metadata.getProperty("tenantId")),
                        blankToNull(metadata.getProperty("matterId")),
                        blankToNull(metadata.getProperty("accessSubjectId")))
                : scopeFromPath(file);
        validateScopePath(file, scope, metadata.containsKey("corpusType"));
        return new LegalDocumentSource(docId, docName, file, scope, objectUri);
    }

    private Properties readSourceMetadata(Path path) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(path)) {
            return properties;
        }
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
            return properties;
        } catch (IOException e) {
            throw new IllegalStateException("failed to read legal source metadata: " + path, e);
        }
    }

    private void writeSourceMetadata(Path path,
                                     String documentId,
                                     String originalName,
                                     LegalDocumentScope scope,
                                     String objectUri) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("documentId", documentId);
        properties.setProperty("originalName", Path.of(originalName).getFileName().toString());
        properties.setProperty("corpusType", scope.corpusType().code());
        if (scope.tenantId() != null) properties.setProperty("tenantId", scope.tenantId());
        if (scope.matterId() != null) properties.setProperty("matterId", scope.matterId());
        if (scope.accessSubjectId() != null) {
            properties.setProperty("accessSubjectId", scope.accessSubjectId());
        }
        if (objectUri != null && !objectUri.isBlank()) properties.setProperty("objectUri", objectUri);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "legal source metadata");
            }
            moveReplacing(temporary, path);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private LegalDocumentScope scopeFromPath(Path file) {
        Path relative = rawDir.relativize(file);
        if (relative.getNameCount() < 2) {
            return LegalDocumentScope.publicLaw();
        }
        LegalCorpusType type;
        try {
            type = LegalCorpusType.fromCode(relative.getName(0).toString());
        } catch (IllegalArgumentException ignored) {
            return LegalDocumentScope.publicLaw();
        }
        return switch (type) {
            case PUBLIC_LAW, PUBLIC_CASE -> new LegalDocumentScope(type, null, null, null);
            case FIRM_KNOWLEDGE -> relative.getNameCount() >= 3
                    ? new LegalDocumentScope(type, relative.getName(1).toString(), null, null)
                    : LegalDocumentScope.publicLaw();
            case MATTER_PRIVATE -> throw new IllegalStateException(
                    "matter-private legal source is missing mandatory scope metadata: " + file);
        };
    }

    private void validateScopePath(Path file, LegalDocumentScope scope, boolean hasMetadata) {
        Path relative = rawDir.relativize(file);
        if (relative.getNameCount() < 2) return;
        LegalCorpusType pathType;
        try {
            pathType = LegalCorpusType.fromCode(relative.getName(0).toString());
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if (!hasMetadata && pathType == LegalCorpusType.MATTER_PRIVATE) {
            throw new IllegalStateException("matter-private legal source is missing mandatory scope metadata: " + file);
        }
        if (scope.corpusType() != pathType) {
            throw new SecurityException("legal source metadata conflicts with corpus directory: " + file);
        }
        if (pathType.tenantRequired()
                && (relative.getNameCount() < 3 || !relative.getName(1).toString().equals(scope.tenantId()))) {
            throw new SecurityException("legal source tenant metadata conflicts with directory: " + file);
        }
        if (pathType.matterRequired()
                && (relative.getNameCount() < 4 || !relative.getName(2).toString().equals(scope.matterId()))) {
            throw new SecurityException("legal source matter metadata conflicts with directory: " + file);
        }
    }

    private Path scopeDirectory(LegalDocumentScope scope) {
        return switch (scope.corpusType()) {
            case PUBLIC_LAW, PUBLIC_CASE -> Path.of(scope.corpusType().code());
            case FIRM_KNOWLEDGE -> Path.of(scope.corpusType().code(), scope.tenantId());
            case MATTER_PRIVATE -> Path.of(scope.corpusType().code(), scope.tenantId(), scope.matterId());
        };
    }

    private Path metadataPath(Path source) {
        return source.resolveSibling(source.getFileName() + ".legal.properties");
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void restoreStagingBackup(Path target,
                                      Path metadata,
                                      Path sourceBackup,
                                      Path metadataBackup,
                                      boolean hadSource,
                                      boolean hadMetadata) throws IOException {
        if (Files.exists(sourceBackup)) {
            Files.deleteIfExists(target);
            moveReplacing(sourceBackup, target);
        } else if (!hadSource) {
            Files.deleteIfExists(target);
        }
        if (Files.exists(metadataBackup)) {
            Files.deleteIfExists(metadata);
            moveReplacing(metadataBackup, metadata);
        } else if (!hadMetadata) {
            Files.deleteIfExists(metadata);
        }
    }

    private void deleteBackupQuietly(Path backup) {
        try {
            Files.deleteIfExists(backup);
        } catch (IOException ignored) {
            // Backups are outside the source scan and can be removed by maintenance.
        }
    }

    private String validateDocumentId(String value) {
        String checked = value == null ? "" : value.trim();
        if (!checked.matches("[a-zA-Z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("documentId contains unsupported characters");
        }
        return checked;
    }

    private String supportedExtension(String originalName) {
        String name = originalName == null ? "" : Path.of(originalName).getFileName().toString().toLowerCase();
        int index = name.lastIndexOf('.');
        String extension = index < 0 ? "" : name.substring(index);
        if (!Set.of(".txt", ".md", ".pdf", ".doc", ".docx").contains(extension)) {
            throw new IllegalArgumentException("unsupported legal document extension");
        }
        return extension;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record DocumentVersion(String docId,
                                   String documentVersion,
                                   String chunkSchemaVersion,
                                   List<LegalDocumentChunk> chunks) {
    }
}
