package cn.apple.domain.legal.model.entity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record LegalDocumentIngestionStats(int totalDocuments,
                                          int successDocuments,
                                          int failedDocuments,
                                          int totalChunks,
                                          List<DocumentStat> documents,
                                          Map<String, Integer> chunksByLaw,
                                          long costMs,
                                          LocalDateTime generatedAt) {

    public record DocumentStat(String docName, String lawName, int chunkCount, String status, String error) {
    }
}
