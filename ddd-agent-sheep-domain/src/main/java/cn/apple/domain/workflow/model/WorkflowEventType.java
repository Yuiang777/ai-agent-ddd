package cn.apple.domain.workflow.model;

public enum WorkflowEventType {
    WORKFLOW_STARTED,
    NODE_STARTED,
    NODE_OUTPUT,
    NODE_COMPLETED,
    NODE_SKIPPED,
    NODE_FAILED,
    REVIEW_REQUIRED,
    WORKFLOW_COMPLETED,
    WORKFLOW_FAILED
}
