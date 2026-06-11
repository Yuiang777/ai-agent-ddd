package cn.apple.domain.workflow.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record AgentContext(String workflowId,
                           String runId,
                           String clientId,
                           String userMessage,
                           String inputText,
                           List<WorkflowAttachment> attachments,
                           Map<String, Object> memory,
                           Map<String, Object> variables,
                           LocalDateTime startTime) {
}

