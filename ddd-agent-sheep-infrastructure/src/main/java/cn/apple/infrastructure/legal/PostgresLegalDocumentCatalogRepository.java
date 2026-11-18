package cn.apple.infrastructure.legal;

import cn.apple.domain.legal.adapter.repository.ILegalDocumentCatalogRepository;
import cn.apple.domain.legal.model.valobj.LegalDocumentRegistration;
import cn.apple.domain.legal.model.valobj.LegalDocumentScope;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

@Repository
@ConditionalOnProperty(name = "app.infrastructure.enabled", havingValue = "true")
public class PostgresLegalDocumentCatalogRepository implements ILegalDocumentCatalogRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresLegalDocumentCatalogRepository(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void register(LegalDocumentRegistration registration) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                upsertDocument(connection, registration);
                upsertVersion(connection, registration);
                upsertAcl(connection, registration);
                connection.commit();
                return null;
            } catch (Exception error) {
                connection.rollback();
                if (error instanceof SQLException sqlException) throw sqlException;
                if (error instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("failed to register legal document", error);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    private void upsertDocument(Connection connection, LegalDocumentRegistration value) throws SQLException {
        LegalDocumentScope scope = value.scope();
        int affected = update(connection, """
                insert into legal_document (
                    document_id, corpus_type, tenant_id, matter_id, owner_subject_id, title, authority_level,
                    status, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                on conflict (document_id) do update
                   set title = excluded.title,
                       authority_level = excluded.authority_level,
                       status = 'ACTIVE',
                       updated_at = excluded.updated_at
                 where legal_document.corpus_type = excluded.corpus_type
                   and legal_document.tenant_id is not distinct from excluded.tenant_id
                   and legal_document.matter_id is not distinct from excluded.matter_id
                   and legal_document.owner_subject_id is not distinct from excluded.owner_subject_id
                """, value.documentId(), scope.corpusType().code(), scope.tenantId(), scope.matterId(),
                scope.accessSubjectId(), value.title(), value.authorityLevel(),
                value.registeredAt(), value.registeredAt());
        if (affected != 1) {
            throw new SecurityException("documentId is already owned by another legal corpus scope");
        }
    }

    private void upsertVersion(Connection connection, LegalDocumentRegistration value) throws SQLException {
        update(connection, """
                insert into legal_document_version (
                    document_id, version_hash, source_checksum, original_name, content_type,
                    size_bytes, object_key, object_uri, supersedes_version, effective_from,
                    effective_to, ingestion_status, registered_by, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(nullif(?, '') as date),
                          cast(nullif(?, '') as date), 'STORED', ?, ?)
                on conflict (document_id, version_hash) do update
                   set object_key = excluded.object_key,
                       object_uri = excluded.object_uri
                 where legal_document_version.source_checksum = excluded.source_checksum
                """, value.documentId(), value.versionHash(), value.object().sha256(),
                value.object().originalName(), value.object().contentType(), value.object().sizeBytes(),
                value.object().objectKey(), value.object().objectUri(), blankToNull(value.supersedesVersion()),
                blank(value.effectiveFrom()), blank(value.effectiveTo()), value.registeredBy(), value.registeredAt());
    }

    private void upsertAcl(Connection connection, LegalDocumentRegistration value) throws SQLException {
        LegalDocumentScope scope = value.scope();
        String subjectType = scope.tenantId() == null
                ? "PUBLIC"
                : scope.accessSubjectId() == null ? "TENANT" : "ACTOR";
        String subjectId = scope.tenantId() == null
                ? "*"
                : scope.accessSubjectId() == null ? scope.tenantId() : scope.accessSubjectId();
        update(connection, """
                insert into legal_document_acl (document_id, subject_type, subject_id, permission, created_at)
                values (?, ?, ?, 'READ', ?)
                on conflict (document_id, subject_type, subject_id, permission) do nothing
                """, value.documentId(), subjectType, subjectId, value.registeredAt());
    }

    private int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                Object value = parameters[index];
                if (value instanceof Instant instant) statement.setTimestamp(index + 1, Timestamp.from(instant));
                else statement.setObject(index + 1, value);
            }
            return statement.executeUpdate();
        }
    }

    private String blank(String value) {
        return value == null ? "" : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
