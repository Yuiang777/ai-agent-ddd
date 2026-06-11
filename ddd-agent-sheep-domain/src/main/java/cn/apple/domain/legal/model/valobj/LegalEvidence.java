package cn.apple.domain.legal.model.valobj;

import java.util.Map;

public record LegalEvidence(String id,
                            String text,
                            String docId,
                            String docName,
                            String lawName,
                            String articleNo,
                            String clauseNo,
                            int chunkIndex,
                            String sourcePath,
                            double score,
                            Map<String, Object> metadata) {

    public String citation() {
        String law = lawName == null || lawName.isBlank() ? docName : "《" + lawName + "》";
        String article = articleNo == null || articleNo.isBlank() ? "" : articleNo;
        String clause = clauseNo == null || clauseNo.isBlank() ? "" : clauseNo;
        return (law + article + clause).trim();
    }
}
