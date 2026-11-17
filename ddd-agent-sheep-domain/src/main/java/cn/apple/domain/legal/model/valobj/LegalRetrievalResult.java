package cn.apple.domain.legal.model.valobj;

import java.util.List;

public record LegalRetrievalResult(List<LegalEvidence> evidences,
                                   String context,
                                   LegalRetrievalDiagnostics diagnostics) {

    public boolean safeForReasoning() {
        return diagnostics != null && diagnostics.safeForReasoning();
    }
}
