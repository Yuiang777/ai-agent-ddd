package cn.apple.domain.legal.model.valobj;

import java.util.List;

public record LegalRetrievalDiagnostics(LegalRetrievalStatus status,
                                        LegalScopeRoute route,
                                        LegalRetrievalSourceDiagnostics vector,
                                        LegalRetrievalSourceDiagnostics keyword,
                                        int evidenceCount,
                                        int contextChars,
                                        int droppedEvidenceCount,
                                        List<String> reviewReasons) {

    public boolean safeForReasoning() {
        return status == LegalRetrievalStatus.HEALTHY && (reviewReasons == null || reviewReasons.isEmpty());
    }
}
