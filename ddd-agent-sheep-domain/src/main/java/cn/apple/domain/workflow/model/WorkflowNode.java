package cn.apple.domain.workflow.model;

import java.util.Map;

public record WorkflowNode(String nodeId,
                           WorkflowNodeType type,
                           String name,
                           Map<String, Object> config) {
}

