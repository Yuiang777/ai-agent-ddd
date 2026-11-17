package cn.apple.domain.workflow.model;

import java.util.List;
import java.util.Map;

public record AgentResult(String outputText,
                          List<Citation> citations,
                          List<RiskItem> risks,
                          Map<String, Object> variables) {
    public static AgentResult ofText(String outputText) {
        return new AgentResult(outputText, List.of(), List.of(), Map.of());
    }
}

