package cn.apple.domain.legal.model.valobj;

import java.util.LinkedHashMap;
import java.util.Map;

public record LegalDocumentScope(LegalCorpusType corpusType,
                                 String tenantId,
                                 String matterId,
                                 String accessSubjectId) {

    public LegalDocumentScope(LegalCorpusType corpusType, String tenantId, String matterId) {
        this(corpusType, tenantId, matterId, null);
    }

    public LegalDocumentScope {
        corpusType = corpusType == null ? LegalCorpusType.PUBLIC_LAW : corpusType;
        tenantId = normalize(tenantId, "tenantId");
        matterId = normalize(matterId, "matterId");
        accessSubjectId = normalize(accessSubjectId, "accessSubjectId");
        if (corpusType.tenantRequired() && tenantId == null) {
            throw new IllegalArgumentException(corpusType.code() + " requires tenantId");
        }
        if (corpusType.matterRequired() && matterId == null) {
            throw new IllegalArgumentException(corpusType.code() + " requires matterId");
        }
        if (corpusType.matterRequired() && accessSubjectId == null) {
            throw new IllegalArgumentException(corpusType.code() + " requires accessSubjectId");
        }
        if (!corpusType.tenantRequired() && (tenantId != null || matterId != null || accessSubjectId != null)) {
            throw new IllegalArgumentException(corpusType.code() + " must not carry private scope identifiers");
        }
        if (!corpusType.matterRequired() && matterId != null) {
            throw new IllegalArgumentException(corpusType.code() + " must not carry matterId");
        }
        if (!corpusType.matterRequired() && accessSubjectId != null) {
            throw new IllegalArgumentException(corpusType.code() + " must not carry accessSubjectId");
        }
    }

    public static LegalDocumentScope publicLaw() {
        return new LegalDocumentScope(LegalCorpusType.PUBLIC_LAW, null, null, null);
    }

    public Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(LegalKnowledgeMetadata.CORPUS_TYPE, corpusType.code());
        if (tenantId != null) {
            metadata.put(LegalKnowledgeMetadata.TENANT_ID, tenantId);
        }
        if (matterId != null) {
            metadata.put(LegalKnowledgeMetadata.MATTER_ID, matterId);
        }
        if (accessSubjectId != null) {
            metadata.put(LegalKnowledgeMetadata.ACL_SUBJECT_ID, accessSubjectId);
        }
        return Map.copyOf(metadata);
    }

    private static String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 128 || !normalized.matches("[\\p{L}\\p{N}._:@-]+")) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return normalized;
    }
}
