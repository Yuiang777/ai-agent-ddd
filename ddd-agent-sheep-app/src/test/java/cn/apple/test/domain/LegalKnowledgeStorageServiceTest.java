package cn.apple.test.domain;

import cn.apple.application.legal.LegalKnowledgeStorageService;
import cn.apple.config.UploadFileValidator;
import cn.apple.config.UploadProperties;
import cn.apple.domain.legal.adapter.repository.ILegalDocumentRepository;
import cn.apple.domain.legal.model.entity.LegalDocument;
import cn.apple.domain.legal.model.entity.LegalDocumentIngestionStats;
import cn.apple.domain.legal.model.valobj.LegalCorpusType;
import cn.apple.domain.legal.model.valobj.LegalDocumentChunk;
import cn.apple.domain.legal.model.valobj.LegalDocumentRegistration;
import cn.apple.domain.legal.model.valobj.LegalDocumentScope;
import cn.apple.domain.legal.model.valobj.LegalDocumentSource;
import cn.apple.infrastructure.legal.FilesystemLegalObjectStorage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LegalKnowledgeStorageServiceTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldPreserveAuthenticatedPrivateScopeAcrossStorageBoundaries() throws Exception {
        AtomicReference<LegalDocumentRegistration> registration = new AtomicReference<>();
        RecordingDocumentRepository documents = new RecordingDocumentRepository(
                temporaryFolder.newFolder("staging").toPath());
        LegalKnowledgeStorageService service = new LegalKnowledgeStorageService(
                new UploadFileValidator(new UploadProperties()),
                new FilesystemLegalObjectStorage(temporaryFolder.newFolder("objects").getAbsolutePath()),
                registration::set,
                documents);
        MockMultipartFile file = new MockMultipartFile(
                "file", "证据.txt", "text/plain", "第一条 已经履行合同。".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        LegalKnowledgeStorageService.LegalDocumentUploadResult result = service.store(
                file, "evidence-1", LegalCorpusType.MATTER_PRIVATE,
                "tenant-1", "matter-2", null, "CLIENT_EVIDENCE",
                null, null, "lawyer-3");

        assertEquals("evidence-1", result.documentId());
        assertEquals(64, result.versionHash().length());
        assertTrue(result.objectUri().startsWith("file:"));
        assertEquals("lawyer-3", result.scope().accessSubjectId());
        assertEquals(result.scope(), registration.get().scope());
        assertEquals("lawyer-3", registration.get().registeredBy());
        assertEquals(result.scope(), documents.stagedScope);
    }

    private static final class RecordingDocumentRepository implements ILegalDocumentRepository {
        private final Path root;
        private LegalDocumentScope stagedScope;

        private RecordingDocumentRepository(Path root) {
            this.root = root;
        }

        @Override public void ensureWorkspace() { }
        @Override public List<LegalDocumentSource> listSources() { return List.of(); }
        @Override public LegalDocument read(LegalDocumentSource source) { throw new UnsupportedOperationException(); }
        @Override public void saveChunks(List<LegalDocumentChunk> chunks) { }
        @Override public void replaceChunks(List<LegalDocumentChunk> chunks) { }
        @Override public void synchronizeChunks(List<LegalDocumentChunk> chunks,
                                                Set<String> sourceDocumentIds,
                                                Set<String> processedDocumentIds) { }
        @Override public void saveManifest(LegalDocumentIngestionStats stats) { }

        @Override
        public LegalDocumentSource stageSource(String documentId,
                                               String originalName,
                                               LegalDocumentScope scope,
                                               String objectUri,
                                               InputStream content) {
            this.stagedScope = scope;
            return new LegalDocumentSource(
                    documentId, originalName, root.resolve(documentId + ".txt"), scope, objectUri);
        }
    }
}
