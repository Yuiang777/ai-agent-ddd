package cn.apple.domain.legal.model.valobj;

public record LegalDocumentChunk(String text,
                                 String docId,
                                 String docName,
                                 String lawName,
                                 String articleNo,
                                 String clauseNo,
                                 int chunkIndex,
                                 String sourcePath,
                                 String sourceChecksum,
                                 String documentVersion,
                                 String chunkSchemaVersion,
                                 String effectiveDate,
                                 String parentRef,
                                 LegalDocumentScope scope,
                                 String objectUri) {

    public LegalDocumentChunk(String text,
                              String docId,
                              String docName,
                              String lawName,
                              String articleNo,
                              String clauseNo,
                              int chunkIndex,
                              String sourcePath,
                              String sourceChecksum,
                              String documentVersion,
                              String chunkSchemaVersion,
                              String effectiveDate,
                              String parentRef) {
        this(text, docId, docName, lawName, articleNo, clauseNo, chunkIndex, sourcePath,
                sourceChecksum, documentVersion, chunkSchemaVersion, effectiveDate, parentRef,
                LegalDocumentScope.publicLaw(), null);
    }

    public LegalDocumentChunk {
        scope = scope == null ? LegalDocumentScope.publicLaw() : scope;
        objectUri = objectUri == null || objectUri.isBlank() ? null : objectUri.trim();
    }
}
