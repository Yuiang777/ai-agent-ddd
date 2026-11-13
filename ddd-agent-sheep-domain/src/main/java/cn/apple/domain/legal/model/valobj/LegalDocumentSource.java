package cn.apple.domain.legal.model.valobj;

import java.nio.file.Path;

public record LegalDocumentSource(String docId,
                                  String docName,
                                  Path sourcePath,
                                  LegalDocumentScope scope,
                                  String objectUri) {

    public LegalDocumentSource(String docId, String docName, Path sourcePath) {
        this(docId, docName, sourcePath, LegalDocumentScope.publicLaw(), null);
    }

    public LegalDocumentSource {
        scope = scope == null ? LegalDocumentScope.publicLaw() : scope;
        objectUri = objectUri == null || objectUri.isBlank() ? null : objectUri.trim();
    }
}
