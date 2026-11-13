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
        String corpusType = metadataValue(LegalKnowledgeMetadata.CORPUS_TYPE);
        boolean publicLaw = corpusType.isBlank()
                ? !value(lawName).isBlank() || !value(articleNo).isBlank()
                : LegalCorpusType.PUBLIC_LAW.code().equals(corpusType);
        if (publicLaw) {
            String law = value(lawName).isBlank() ? value(docName) : "《" + lawName + "》";
            return (law + value(articleNo) + value(clauseNo)).trim();
        }

        String document = firstNonBlank(docName, lawName, docId, "未知文档");
        String section = firstNonBlank(
                metadataValue(LegalKnowledgeMetadata.SECTION_PATH),
                metadataValue(LegalKnowledgeMetadata.PARENT_REF));
        return section.isBlank() ? document : document + " / " + section;
    }

    private String metadataValue(String key) {
        return metadata == null ? "" : value(metadata.get(key));
    }

    private String firstNonBlank(Object... values) {
        for (Object candidate : values) {
            String normalized = value(candidate);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String value(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
