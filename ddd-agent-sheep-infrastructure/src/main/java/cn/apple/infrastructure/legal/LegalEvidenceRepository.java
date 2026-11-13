package cn.apple.infrastructure.legal;

import cn.apple.domain.legal.adapter.repository.ILegalEvidenceRepository;
import cn.apple.domain.legal.model.valobj.LegalEvidence;
import cn.apple.domain.legal.model.valobj.LegalKnowledgeMetadata;
import cn.apple.domain.legal.model.valobj.LegalRetrievalRequest;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Repository
@ConditionalOnProperty(name = "app.infrastructure.enabled", havingValue = "true")
public class LegalEvidenceRepository implements ILegalEvidenceRepository {

    private static final int MAX_KEYWORD_TERMS = 12;
    private static final int MAX_TERM_LENGTH = 64;
    private static final int MAX_SIMILARITY_QUERY_LENGTH = 512;
    private static final Pattern ARTICLE_REFERENCE = Pattern.compile(
            "第[0-9０-９一二三四五六七八九十百千万零〇两]+条(?:之[0-9０-９一二三四五六七八九十百千万零〇两]+)?");

    private final VectorStore vectorStore;
    private final JdbcTemplate pgVectorJdbcTemplate;
    private final String vectorTable;

    public LegalEvidenceRepository(@Qualifier("vectorStore") VectorStore vectorStore,
                                   @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                                   @Value("${app.legal.knowledge.vector-table:"
                                           + LegalKnowledgeMetadata.DEFAULT_VECTOR_TABLE + "}") String vectorTable) {
        this.vectorStore = vectorStore;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.vectorTable = validateTableName(vectorTable);
    }

    @Override
    public List<LegalEvidence> vectorSearch(LegalRetrievalRequest request) {
        request.metadataFilters();
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(request.semanticQuery())
                .topK(request.normalizedVectorTopK());
        builder.filterExpression(new FilterExpressionTextParser().parse(request.metadataFilterExpression()));
        List<Document> documents = vectorStore.similaritySearch(builder.build());
        if (documents == null) {
            return List.of();
        }
        return documents.stream().map(this::fromDocument).toList();
    }

    @Override
    public List<LegalEvidence> keywordSearch(LegalRetrievalRequest request) {
        Map<String, String> metadataFilters = request.metadataFilters();
        String similarityQuery = normalizedSimilarityQuery(request.query());
        List<String> terms = keywordTerms(request);
        List<String> lawNames = lawNameHints(request);
        List<String> articleReferences = articleReferences(request.query());
        List<Object> parameters = new ArrayList<>();

        StringBuilder score = new StringBuilder("greatest(similarity(content, ?), word_similarity(?, content))");
        parameters.add(similarityQuery);
        parameters.add(similarityQuery);
        for (String term : terms) {
            score.append(" + case when content ilike ? then 0.08 else 0 end");
            parameters.add(likePattern(term));
        }
        for (String lawName : lawNames) {
            score.append(" + case when coalesce(metadata ->> '")
                    .append(LegalKnowledgeMetadata.LAW_NAME)
                    .append("', '') ilike ? then 0.45 else 0 end");
            parameters.add(likePattern(lawName));
        }
        for (String articleReference : articleReferences) {
            score.append(" + case when metadata ->> '")
                    .append(LegalKnowledgeMetadata.ARTICLE_NO)
                    .append("' = ? then 0.75 else 0 end");
            parameters.add(articleReference);
        }

        StringBuilder sql = new StringBuilder("""
                select id::text as id,
                       content,
                       metadata,
                       (""").append(score).append(") as keyword_score\n")
                .append("  from ").append(vectorTable).append("\n")
                .append(" where 1 = 1\n");
        metadataFilters.forEach((key, value) -> {
            sql.append("   and metadata ->> '").append(key).append("' = ?\n");
            parameters.add(value);
        });

        sql.append("   and (content % ?");
        parameters.add(similarityQuery);
        for (String term : terms) {
            sql.append(" or content ilike ?");
            parameters.add(likePattern(term));
        }
        for (String lawName : lawNames) {
            sql.append(" or coalesce(metadata ->> '")
                    .append(LegalKnowledgeMetadata.LAW_NAME)
                    .append("', '') ilike ?");
            parameters.add(likePattern(lawName));
        }
        for (String articleReference : articleReferences) {
            sql.append(" or metadata ->> '")
                    .append(LegalKnowledgeMetadata.ARTICLE_NO)
                    .append("' = ?");
            parameters.add(articleReference);
        }
        sql.append(")\n order by keyword_score desc, id\n limit ?");
        parameters.add(request.normalizedKeywordTopK());

        return pgVectorJdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> fromRow(
                        rs.getString("id"),
                        rs.getString("content"),
                        rs.getString("metadata"),
                        rs.getDouble("keyword_score")),
                parameters.toArray());
    }

    private LegalEvidence fromDocument(Document document) {
        Map<String, Object> metadata = document.getMetadata() == null ? Map.of() : new LinkedHashMap<>(document.getMetadata());
        return new LegalEvidence(
                document.getId() == null || document.getId().isBlank()
                        ? string(metadata.get("id"))
                        : document.getId(),
                document.getText(),
                string(metadata.get(LegalKnowledgeMetadata.DOC_ID)),
                string(metadata.get(LegalKnowledgeMetadata.DOC_NAME)),
                string(metadata.get(LegalKnowledgeMetadata.LAW_NAME)),
                string(metadata.get(LegalKnowledgeMetadata.ARTICLE_NO)),
                string(metadata.get(LegalKnowledgeMetadata.CLAUSE_NO)),
                integer(metadata.get(LegalKnowledgeMetadata.CHUNK_INDEX)),
                string(metadata.get(LegalKnowledgeMetadata.SOURCE_PATH)),
                doubleValue(metadata.get("distance")),
                metadata
        );
    }

    private LegalEvidence fromRow(String id, String text, String metadataJson, double score) {
        Map<String, Object> metadata = LegalMetadataJson.parse(metadataJson);
        return new LegalEvidence(
                id,
                text,
                string(metadata.get(LegalKnowledgeMetadata.DOC_ID)),
                string(metadata.get(LegalKnowledgeMetadata.DOC_NAME)),
                string(metadata.get(LegalKnowledgeMetadata.LAW_NAME)),
                string(metadata.get(LegalKnowledgeMetadata.ARTICLE_NO)),
                string(metadata.get(LegalKnowledgeMetadata.CLAUSE_NO)),
                integer(metadata.get(LegalKnowledgeMetadata.CHUNK_INDEX)),
                string(metadata.get(LegalKnowledgeMetadata.SOURCE_PATH)),
                score,
                metadata
        );
    }

    private String normalizedSimilarityQuery(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_SIMILARITY_QUERY_LENGTH
                ? normalized
                : normalized.substring(0, MAX_SIMILARITY_QUERY_LENGTH);
    }

    private List<String> keywordTerms(LegalRetrievalRequest request) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addTerms(terms, request.query());
        if (request.route() != null && request.route().queryExpansionTerms() != null) {
            request.route().queryExpansionTerms().forEach(term -> addTerm(terms, term));
        }
        return terms.stream().limit(MAX_KEYWORD_TERMS).toList();
    }

    private void addTerms(Set<String> terms, String value) {
        if (value == null) {
            return;
        }
        for (String term : value.split("[\\s，。！？；、,.;:：!?()（）]+")) {
            addTerm(terms, term);
            if (terms.size() >= MAX_KEYWORD_TERMS) {
                return;
            }
        }
    }

    private void addTerm(Set<String> terms, String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < 2) {
            return;
        }
        terms.add(normalized.length() <= MAX_TERM_LENGTH
                ? normalized
                : normalized.substring(0, MAX_TERM_LENGTH));
    }

    private List<String> lawNameHints(LegalRetrievalRequest request) {
        if (request.route() == null || request.route().lawNameHints() == null) {
            return List.of();
        }
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        request.route().lawNameHints().forEach(value -> {
            if (value != null && !value.isBlank()) {
                hints.add(value.trim());
            }
        });
        return hints.stream().limit(8).toList();
    }

    private List<String> articleReferences(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> references = new LinkedHashSet<>();
        Matcher matcher = ARTICLE_REFERENCE.matcher(query);
        while (matcher.find() && references.size() < 8) {
            references.add(matcher.group());
        }
        return List.copyOf(references);
    }

    private String likePattern(String value) {
        String escaped = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private String validateTableName(String value) {
        String checked = value == null ? "" : value.trim();
        if (!checked.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("app.legal.knowledge.vector-table must be a simple lowercase SQL identifier");
        }
        return checked;
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
