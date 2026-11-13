package cn.apple.domain.legal.model.valobj;

import java.time.Instant;

public record LegalDocumentRegistration(String documentId,
                                        LegalDocumentScope scope,
                                        String title,
                                        StoredLegalObject object,
                                        String versionHash,
                                        String supersedesVersion,
                                        String authorityLevel,
                                        String effectiveFrom,
                                        String effectiveTo,
                                        String registeredBy,
                                        Instant registeredAt) {
    public LegalDocumentRegistration {
        if (documentId == null || !documentId.trim().matches("[a-zA-Z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("documentId contains unsupported characters");
        }
        documentId = documentId.trim();
        scope = scope == null ? LegalDocumentScope.publicLaw() : scope;
        if (object == null) {
            throw new IllegalArgumentException("stored legal object is required");
        }
        if (versionHash == null || !versionHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("versionHash must be a SHA-256 value");
        }
        title = title == null || title.isBlank() ? object.originalName() : title.trim();
        authorityLevel = authorityLevel == null || authorityLevel.isBlank() ? "UNVERIFIED" : authorityLevel.trim();
        if (authorityLevel.length() > 64 || !authorityLevel.matches("[\\p{L}\\p{N}._:-]+")) {
            throw new IllegalArgumentException("authorityLevel contains unsupported characters");
        }
        if (registeredBy == null || registeredBy.isBlank() || registeredBy.length() > 128
                || !registeredBy.matches("[\\p{L}\\p{N}._:@-]+")) {
            throw new IllegalArgumentException("registeredBy contains unsupported characters");
        }
        registeredBy = registeredBy.trim();
        if (scope.corpusType().matterRequired() && !registeredBy.equals(scope.accessSubjectId())) {
            throw new IllegalArgumentException("private legal document owner must match registeredBy");
        }
        if (supersedesVersion != null && supersedesVersion.equals(versionHash)) {
            throw new IllegalArgumentException("a legal document version cannot supersede itself");
        }
        registeredAt = registeredAt == null ? Instant.now() : registeredAt;
    }
}
