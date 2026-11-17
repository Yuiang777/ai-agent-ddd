package cn.apple.domain.legal.matter;

public record ReviewArtifactBinding(long artifactVersion, String artifactSha256, String evidenceFingerprint) {
    public ReviewArtifactBinding {
        if (artifactVersion < 1 || artifactSha256 == null || !artifactSha256.matches("[0-9a-f]{64}")
                || evidenceFingerprint == null || !evidenceFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("INVALID_REVIEW_ARTIFACT_BINDING");
        }
    }
}
