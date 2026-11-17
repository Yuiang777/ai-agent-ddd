package cn.apple.domain.workflow.model;

public record WorkflowEdge(String fromNodeId,
                           String toNodeId,
                           String conditionExpression) {
}

