package cn.apple.infrastructure.legal;

import cn.apple.domain.legal.adapter.repository.ILegalEvidenceRepository;
import cn.apple.domain.legal.model.valobj.LegalEvidence;
import cn.apple.domain.legal.model.valobj.LegalRetrievalRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class LegalEvidenceRepository implements ILegalEvidenceRepository {

    private final VectorStore vectorStore;
    @Qualifier("pgVectorJdbcTemplate")
    private final JdbcTemplate pgVectorJdbcTemplate;

    @Override
    public List<LegalEvidence> vectorSearch(LegalRetrievalRequest request) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(request.query())
                .topK(request.normalizedVectorTopK());
        if (StringUtils.hasText(request.filterExpression())) {
            builder.filterExpression(new FilterExpressionTextParser().parse(request.filterExpression()));
        }
        List<Document> documents = vectorStore.similaritySearch(builder.build());
        if (documents == null) {
            return List.of();
        }
        return documents.stream().map(this::fromDocument).toList();
    }

    @Override
    public List<LegalEvidence> keywordSearch(LegalRetrievalRequest request) {
        String sql = """
                select id::text as id,
                       content,
                       metadata,
                       ts_rank_cd(to_tsvector('simple', content), plainto_tsquery('simple', ?)) as keyword_score
                from vector_store_openai
                where metadata ->> 'knowledge' = 'law-consultant'
                  and to_tsvector('simple', content) @@ plainto_tsquery('simple', ?)
                order by keyword_score desc
                limit ?
                """;
        return pgVectorJdbcTemplate.query(sql,
                (rs, rowNum) -> fromRow(
                        rs.getString("id"),
                        rs.getString("content"),
                        rs.getString("metadata"),
                        rs.getDouble("keyword_score")),
                request.keywordQuery(),
                request.keywordQuery(),
                request.normalizedKeywordTopK());
    }

    private LegalEvidence fromDocument(Document document) {
        Map<String, Object> metadata = document.getMetadata() == null ? Map.of() : new LinkedHashMap<>(document.getMetadata());
        return new LegalEvidence(
                string(metadata.get("id")),
                document.getText(),
                string(metadata.get("doc_id")),
                string(metadata.get("doc_name")),
                string(metadata.get("law_name")),
                string(metadata.get("article_no")),
                string(metadata.get("clause_no")),
                integer(metadata.get("chunk_index")),
                string(metadata.get("source_path")),
                doubleValue(metadata.get("distance")),
                metadata
        );
    }

    private LegalEvidence fromRow(String id, String text, String metadataJson, double score) {
        Map<String, Object> metadata = LegalMetadataJson.parse(metadataJson);
        return new LegalEvidence(
                id,
                text,
                string(metadata.get("doc_id")),
                string(metadata.get("doc_name")),
                string(metadata.get("law_name")),
                string(metadata.get("article_no")),
                string(metadata.get("clause_no")),
                integer(metadata.get("chunk_index")),
                string(metadata.get("source_path")),
                score,
                metadata
        );
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0.0 : Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return 0.0;
        }
    }
}
