package cn.apple.domain.legal.model.valobj;

public record LegalRetrievalSourceDiagnostics(String source,
                                              boolean available,
                                              int resultCount,
                                              long costMs,
                                              String errorCode) {

    public static LegalRetrievalSourceDiagnostics success(String source, int resultCount, long costMs) {
        return new LegalRetrievalSourceDiagnostics(source, true, resultCount, costMs, null);
    }

    public static LegalRetrievalSourceDiagnostics failed(String source, long costMs, Throwable error) {
        String errorCode = error == null ? "UNKNOWN" : error.getClass().getSimpleName();
        return new LegalRetrievalSourceDiagnostics(source, false, 0, costMs, errorCode);
    }
}
