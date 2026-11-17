package cn.apple.domain.legal.matter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

public record MatterArtifact(long version, String content, String sha256, String workflowRunId,
                             String createdBy, Instant createdAt) {
    public MatterArtifact {
        if (version < 1 || content == null || content.isBlank() || content.length() > 200000) {
            throw new IllegalArgumentException("INVALID_ARTIFACT_CONTENT_OR_VERSION");
        }
        if (!digest(content).equals(sha256)) throw new IllegalArgumentException("ARTIFACT_DIGEST_MISMATCH");
        if (createdBy == null || createdBy.isBlank() || createdAt == null) {
            throw new IllegalArgumentException("ARTIFACT_ACTOR_AND_TIME_REQUIRED");
        }
        workflowRunId = workflowRunId == null ? "" : workflowRunId.trim();
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
