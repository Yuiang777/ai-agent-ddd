package cn.apple.domain.workflow.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record WorkflowRunResult(String workflowId,
                                String runId,
                                WorkflowStatus status,
                                String finalOutput,
                                List<Citation> citations,
                                List<RiskItem> risks,
                                Map<String, Object> variables,
                                long costMs,
                                LocalDateTime startedAt,
                                LocalDateTime finishedAt) {
}

