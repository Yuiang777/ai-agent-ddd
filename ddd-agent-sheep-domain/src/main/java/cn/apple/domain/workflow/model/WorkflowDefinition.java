package cn.apple.domain.workflow.model;

import java.util.List;
import java.util.Map;

public record WorkflowDefinition(String workflowId,
                                 String workflowName,
                                 String startNodeId,
                                 String endNodeId,
                                 List<WorkflowNode> nodes,
                                 List<WorkflowEdge> edges,
                                 Map<String, Object> config) {
}

