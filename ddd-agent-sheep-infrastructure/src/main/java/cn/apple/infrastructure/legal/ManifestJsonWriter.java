package cn.apple.infrastructure.legal;

import cn.apple.domain.legal.model.entity.LegalDocumentIngestionStats;
import cn.apple.domain.legal.model.entity.LegalDocumentIngestionStats.DocumentStat;

import java.util.Map;

class ManifestJsonWriter {

    static String toJson(LegalDocumentIngestionStats stats) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        appendField(builder, "totalDocuments", stats.totalDocuments()).append(",\n");
        appendField(builder, "successDocuments", stats.successDocuments()).append(",\n");
        appendField(builder, "failedDocuments", stats.failedDocuments()).append(",\n");
        appendField(builder, "totalChunks", stats.totalChunks()).append(",\n");
        appendField(builder, "costMs", stats.costMs()).append(",\n");
        appendField(builder, "generatedAt", stats.generatedAt().toString()).append(",\n");
        builder.append("  \"chunksByLaw\": {\n");
        int lawIndex = 0;
        for (Map.Entry<String, Integer> entry : stats.chunksByLaw().entrySet()) {
            builder.append("    \"").append(escape(entry.getKey())).append("\": ").append(entry.getValue());
            if (++lawIndex < stats.chunksByLaw().size()) {
                builder.append(',');
            }
            builder.append('\n');
        }
        builder.append("  },\n");
        builder.append("  \"documents\": [\n");
        for (int i = 0; i < stats.documents().size(); i++) {
            DocumentStat doc = stats.documents().get(i);
            builder.append("    {");
            builder.append("\"docName\": \"").append(escape(doc.docName())).append("\", ");
            builder.append("\"lawName\": \"").append(escape(doc.lawName())).append("\", ");
            builder.append("\"chunkCount\": ").append(doc.chunkCount()).append(", ");
            builder.append("\"status\": \"").append(escape(doc.status())).append("\", ");
            builder.append("\"error\": \"").append(escape(doc.error())).append("\"}");
            if (i + 1 < stats.documents().size()) {
                builder.append(',');
            }
            builder.append('\n');
        }
        builder.append("  ]\n");
        builder.append("}\n");
        return builder.toString();
    }

    private static StringBuilder appendField(StringBuilder builder, String name, Object value) {
        builder.append("  \"").append(name).append("\": ");
        if (value instanceof Number) {
            builder.append(value);
        } else {
            builder.append("\"").append(escape(value == null ? null : value.toString())).append("\"");
        }
        return builder;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
