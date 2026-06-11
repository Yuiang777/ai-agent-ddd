package cn.apple.domain.legal.model.valobj;

public record LegalRetrievalRequest(String query,
                                    String filterExpression,
                                    int topK,
                                    int vectorTopK,
                                    int keywordTopK,
                                    LegalScopeRoute route) {

    public LegalRetrievalRequest(String query, String filterExpression, int topK, int vectorTopK, int keywordTopK) {
        this(query, filterExpression, topK, vectorTopK, keywordTopK, null);
    }

    public int normalizedTopK() {
        return topK <= 0 ? 6 : topK;
    }

    public int normalizedVectorTopK() {
        return vectorTopK <= 0 ? Math.max(12, normalizedTopK() * 3) : vectorTopK;
    }

    public int normalizedKeywordTopK() {
        return keywordTopK <= 0 ? Math.max(12, normalizedTopK() * 3) : keywordTopK;
    }

    public String keywordQuery() {
        return route == null ? query : route.expandedQuery(query);
    }
}
