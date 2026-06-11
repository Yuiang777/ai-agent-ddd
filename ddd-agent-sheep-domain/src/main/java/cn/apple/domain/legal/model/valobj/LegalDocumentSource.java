package cn.apple.domain.legal.model.valobj;

import java.nio.file.Path;

public record LegalDocumentSource(String docId, String docName, Path sourcePath) {
}
