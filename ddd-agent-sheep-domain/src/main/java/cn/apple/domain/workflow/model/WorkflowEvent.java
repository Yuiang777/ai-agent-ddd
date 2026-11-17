package cn.apple.domain.workflow.model;

import java.time.LocalDateTime;
import java.util.Map;

public record WorkflowEvent(WorkflowEventType type,
                            String workflowId,
                            String runId,
                            String nodeId,
                            WorkflowNodeType nodeType,
                            String message,
                            Map<String, Object> data,
                            LocalDateTime timestamp) {
}

