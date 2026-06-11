package cn.apple.domain.legal.model.valobj;

public record LegalDocumentChunk(String text,
                                 String docId,
                                 String docName,
                                 String lawName,
                                 String articleNo,
                                 String clauseNo,
                                 int chunkIndex,
                                 String sourcePath) {
}
