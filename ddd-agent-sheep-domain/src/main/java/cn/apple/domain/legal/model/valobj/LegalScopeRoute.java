package cn.apple.domain.legal.model.valobj;

import java.util.List;
import java.util.Map;

public record LegalScopeRoute(String scopeCode,
                              String scopeName,
                              List<String> lawNameHints,
                              List<String> queryExpansionTerms,
                              double confidence,
                              String reason) {

    public static LegalScopeRoute general() {
        return new LegalScopeRoute(
                "general_civil",
                "通用民商事",
                List.of("民法典"),
                List.of("民事", "合同", "权利义务", "违约责任", "民法典"),
                0.35,
                "未命中明确专门领域，采用通用民商事范围"
        );
    }

    public String expandedQuery(String query) {
        String base = query == null ? "" : query.trim();
        if (queryExpansionTerms == null || queryExpansionTerms.isEmpty()) {
            return base;
        }
        return (base + " " + String.join(" ", queryExpansionTerms)).trim();
    }

    public Map<String, Object> metadata() {
        return Map.of(
                "scope_code", scopeCode == null ? "" : scopeCode,
                "scope_name", scopeName == null ? "" : scopeName,
                "scope_reason", reason == null ? "" : reason,
                "scope_confidence", confidence
        );
    }
}
