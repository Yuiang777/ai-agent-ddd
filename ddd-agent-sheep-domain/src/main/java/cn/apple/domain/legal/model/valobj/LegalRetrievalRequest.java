package cn.apple.domain.legal.model.valobj;

import org.springframework.ai.vectorstore.filter.Filter;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record LegalRetrievalRequest(String query,
                                    String filterExpression,
                                    int topK,
                                    int vectorTopK,
                                    int keywordTopK,
                                    LegalScopeRoute route,
                                    String knowledgeTag,
                                    String tenantId,
                                    LegalCorpusType corpusType,
                                    String matterId,
                                    String actorId) {

    private static final int MAX_TOP_K = 50;
    private static final int MAX_SOURCE_TOP_K = 200;
    private static final int MAX_FILTER_LENGTH = 512;
    private static final int MAX_REWRITTEN_QUERY_LENGTH = 1200;
    private static final int MAX_QUERY_EXPANSION_TERMS = 8;
    private static final Pattern FILTER_CLAUSE = Pattern.compile(
            "^([a-z][a-z0-9_]*)\\s*==\\s*'([\\p{L}\\p{N} ._:@/-]{1,128})'$");
    private static final Set<String> ALLOWED_FILTER_KEYS = Set.of(
            LegalKnowledgeMetadata.KNOWLEDGE,
            LegalKnowledgeMetadata.CORPUS_TYPE,
            LegalKnowledgeMetadata.TENANT_ID,
            LegalKnowledgeMetadata.MATTER_ID,
            LegalKnowledgeMetadata.ACL_SUBJECT_ID,
            LegalKnowledgeMetadata.INGESTION_STATUS,
            LegalKnowledgeMetadata.DOC_ID,
            LegalKnowledgeMetadata.LAW_NAME,
            LegalKnowledgeMetadata.ARTICLE_NO,
            LegalKnowledgeMetadata.EFFECTIVE_DATE,
            LegalKnowledgeMetadata.DOCUMENT_VERSION,
            "status"
    );

    public LegalRetrievalRequest(String query, String filterExpression, int topK, int vectorTopK, int keywordTopK) {
        this(query, filterExpression, topK, vectorTopK, keywordTopK, null,
                LegalKnowledgeMetadata.DEFAULT_KNOWLEDGE_TAG, null, LegalCorpusType.PUBLIC_LAW, null, null);
    }

    public LegalRetrievalRequest(String query,
                                 String filterExpression,
                                 int topK,
                                 int vectorTopK,
                                 int keywordTopK,
                                 LegalScopeRoute route) {
        this(query, filterExpression, topK, vectorTopK, keywordTopK, route,
                LegalKnowledgeMetadata.DEFAULT_KNOWLEDGE_TAG, null, LegalCorpusType.PUBLIC_LAW, null, null);
    }

    public LegalRetrievalRequest(String query,
                                 String filterExpression,
                                 int topK,
                                 int vectorTopK,
                                 int keywordTopK,
                                 LegalScopeRoute route,
                                 String knowledgeTag,
                                 String tenantId) {
        this(query, filterExpression, topK, vectorTopK, keywordTopK, route, knowledgeTag, tenantId,
                tenantId == null || tenantId.isBlank()
                        ? LegalCorpusType.PUBLIC_LAW
                        : LegalCorpusType.FIRM_KNOWLEDGE,
                null, null);
    }

    public LegalRetrievalRequest(String query,
                                 String filterExpression,
                                 int topK,
                                 int vectorTopK,
                                 int keywordTopK,
                                 LegalScopeRoute route,
                                 String knowledgeTag,
                                 String tenantId,
                                 LegalCorpusType corpusType,
                                 String matterId) {
        this(query, filterExpression, topK, vectorTopK, keywordTopK, route,
                knowledgeTag, tenantId, corpusType, matterId, null);
    }

    public int normalizedTopK() {
        return topK <= 0 ? 6 : Math.min(MAX_TOP_K, topK);
    }

    public int normalizedVectorTopK() {
        return vectorTopK <= 0 ? Math.max(12, normalizedTopK() * 3) : Math.min(MAX_SOURCE_TOP_K, vectorTopK);
    }

    public int normalizedKeywordTopK() {
        return keywordTopK <= 0 ? Math.max(12, normalizedTopK() * 3) : Math.min(MAX_SOURCE_TOP_K, keywordTopK);
    }

    public String keywordQuery() {
        return route == null ? query : route.expandedQuery(query);
    }

    public String semanticQuery() {
        String base = query == null ? "" : query.replaceAll("\\s+", " ").trim();
        if (route == null || route.confidence() < 0.5) {
            return base;
        }
        Set<String> additions = new LinkedHashSet<>();
        addRewriteTerms(additions, route.lawNameHints(), base);
        addRewriteTerms(additions, route.queryExpansionTerms(), base);
        String rewritten = additions.isEmpty() ? base : base + " " + String.join(" ", additions);
        return rewritten.length() <= MAX_REWRITTEN_QUERY_LENGTH
                ? rewritten
                : rewritten.substring(0, MAX_REWRITTEN_QUERY_LENGTH).strip();
    }

    public String normalizedKnowledgeTag() {
        return validatedMetadataValue(
                knowledgeTag == null || knowledgeTag.isBlank()
                        ? LegalKnowledgeMetadata.DEFAULT_KNOWLEDGE_TAG
                        : knowledgeTag,
                "knowledgeTag");
    }

    public String normalizedTenantId() {
        return tenantId == null || tenantId.isBlank() ? null : validatedMetadataValue(tenantId, "tenantId");
    }

    public String normalizedMatterId() {
        return matterId == null || matterId.isBlank() ? null : validatedMetadataValue(matterId, "matterId");
    }

    public String normalizedActorId() {
        return actorId == null || actorId.isBlank() ? null : validatedMetadataValue(actorId, "actorId");
    }

    public LegalDocumentScope normalizedDocumentScope() {
        return new LegalDocumentScope(
                corpusType == null ? LegalCorpusType.PUBLIC_LAW : corpusType,
                normalizedTenantId(),
                normalizedMatterId(),
                normalizedActorId());
    }

    public String metadataFilterExpression() {
        return metadataFilters().entrySet().stream()
                .map(entry -> entry.getKey() + " == '" + entry.getValue() + "'")
                .reduce((left, right) -> left + " && " + right)
                .orElseThrow();
    }

    public Map<String, String> metadataFilters() {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put(LegalKnowledgeMetadata.KNOWLEDGE, normalizedKnowledgeTag());
        filters.put(LegalKnowledgeMetadata.INGESTION_STATUS, LegalKnowledgeMetadata.STATUS_ACTIVE);
        LegalDocumentScope scope = normalizedDocumentScope();
        filters.put(LegalKnowledgeMetadata.CORPUS_TYPE, scope.corpusType().code());
        if (scope.tenantId() != null) filters.put(LegalKnowledgeMetadata.TENANT_ID, scope.tenantId());
        if (scope.matterId() != null) filters.put(LegalKnowledgeMetadata.MATTER_ID, scope.matterId());
        if (scope.accessSubjectId() != null) {
            filters.put(LegalKnowledgeMetadata.ACL_SUBJECT_ID, scope.accessSubjectId());
        }
        if (filterExpression == null || filterExpression.isBlank()) {
            return Collections.unmodifiableMap(filters);
        }

        String filter = stripOuterParentheses(filterExpression.trim());
        if (filter.length() > MAX_FILTER_LENGTH || filter.contains("||")
                || filter.contains(";") || filter.contains("--") || filter.contains("/*")) {
            throw new IllegalArgumentException("filterExpression contains unsupported content");
        }
        for (String rawClause : filter.split("\\s*&&\\s*", -1)) {
            String clause = stripOuterParentheses(rawClause.trim());
            Matcher matcher = FILTER_CLAUSE.matcher(clause);
            if (!matcher.matches() || !ALLOWED_FILTER_KEYS.contains(matcher.group(1))) {
                throw new IllegalArgumentException("filterExpression supports only approved metadata equality filters");
            }
            String key = matcher.group(1);
            String value = matcher.group(2).trim();
            String existing = filters.putIfAbsent(key, value);
            if (existing != null && !existing.equals(value)) {
                throw new IllegalArgumentException("filterExpression conflicts with mandatory filter: " + key);
            }
        }
        return Collections.unmodifiableMap(filters);
    }

    public static String serializeSupportedFilter(Filter.Expression expression) {
        if (expression == null) {
            return null;
        }
        if (expression.type() == Filter.ExpressionType.AND) {
            return "(" + serializeOperand(expression.left()) + ") && ("
                    + serializeOperand(expression.right()) + ")";
        }
        if (expression.type() != Filter.ExpressionType.EQ
                || !(expression.left() instanceof Filter.Key key)
                || !(expression.right() instanceof Filter.Value value)) {
            throw new IllegalArgumentException("only metadata equality conjunction filters are supported");
        }
        String clause = key.key() + " == '" + String.valueOf(value.value()) + "'";
        Matcher matcher = FILTER_CLAUSE.matcher(clause);
        if (!matcher.matches() || !ALLOWED_FILTER_KEYS.contains(key.key())) {
            throw new IllegalArgumentException("filter contains unsupported metadata key or value");
        }
        return clause;
    }

    private String validatedMetadataValue(String value, String field) {
        String checked = value == null ? "" : value.trim();
        if (checked.isEmpty() || checked.length() > 128 || !checked.matches("[\\p{L}\\p{N}._:@-]+")) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return checked;
    }

    private void addRewriteTerms(Set<String> additions, Iterable<String> values, String base) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            String term = value == null ? "" : value.replaceAll("\\s+", " ").trim();
            if (!term.isBlank() && term.length() <= 64 && !base.contains(term)) {
                additions.add(term);
            }
            if (additions.size() >= MAX_QUERY_EXPANSION_TERMS) {
                return;
            }
        }
    }

    private String stripOuterParentheses(String value) {
        String checked = value;
        while (checked.length() >= 2 && checked.charAt(0) == '('
                && checked.charAt(checked.length() - 1) == ')' && enclosesWholeExpression(checked)) {
            checked = checked.substring(1, checked.length() - 1).trim();
        }
        return checked;
    }

    private boolean enclosesWholeExpression(String value) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\'') {
                quoted = !quoted;
            } else if (!quoted && current == '(') {
                depth++;
            } else if (!quoted && current == ')' && --depth == 0 && i < value.length() - 1) {
                return false;
            }
            if (depth < 0) {
                return false;
            }
        }
        return depth == 0 && !quoted;
    }

    private static String serializeOperand(Filter.Operand operand) {
        if (operand instanceof Filter.Expression expression) {
            return serializeSupportedFilter(expression);
        }
        throw new IllegalArgumentException("filter conjunction operands must be expressions");
    }
}
