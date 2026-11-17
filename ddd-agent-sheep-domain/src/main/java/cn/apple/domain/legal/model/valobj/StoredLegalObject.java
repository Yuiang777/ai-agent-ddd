package cn.apple.domain.legal.model.valobj;

import java.time.Instant;

public record StoredLegalObject(String objectKey,
                                String objectUri,
                                String originalName,
                                String contentType,
                                long sizeBytes,
                                String sha256,
                                Instant storedAt) {
}
