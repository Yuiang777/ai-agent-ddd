package cn.apple.domain.legal.model.valobj;

public record LegalObjectWriteRequest(String objectKey,
                                      String originalName,
                                      String contentType,
                                      long sizeBytes,
                                      String sha256) {
    public LegalObjectWriteRequest {
        if (objectKey == null || objectKey.isBlank() || objectKey.startsWith("/")
                || objectKey.contains("..") || objectKey.contains("\\")) {
            throw new IllegalArgumentException("objectKey must be a safe relative key");
        }
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("originalName must not be blank");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        sha256 = sha256 == null ? "" : sha256.trim().toLowerCase();
        if (!sha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("sha256 must contain 64 lowercase hex characters");
        }
        contentType = contentType == null || contentType.isBlank()
                ? "application/octet-stream"
                : contentType.trim().toLowerCase();
    }
}
