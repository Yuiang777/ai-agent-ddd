package cn.apple.domain.workflow.model;

public record WorkflowAttachment(String attachmentId,
                                 String fileName,
                                 String contentType,
                                 String url,
                                 String localPath) {
}
