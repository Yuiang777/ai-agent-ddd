package cn.apple.domain.legal.matter;

import java.time.Instant;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;

/** Evidence custody and integrity metadata; content remains in object storage. */
public record MatterEvidence(String id,
                             String tenantId,
                             String matterId,
                             int version,
                             String objectReference,
                             String sha256,
                             EvidenceSourceType sourceType,
                             Instant acquiredAt,
                             String custodian,
                             EvidencePreservationStatus preservationStatus,
                             Set<String> issueIds) {
    public MatterEvidence {
        id = required(id, "id");
        tenantId = required(tenantId, "tenantId");
        matterId = required(matterId, "matterId");
        objectReference = required(objectReference, "objectReference");
        sha256 = required(sha256, "sha256").toLowerCase(Locale.ROOT);
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 must be 64 hex characters");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        sourceType = sourceType == null ? EvidenceSourceType.OTHER : sourceType;
        acquiredAt = acquiredAt == null ? Instant.now() : acquiredAt;
        custodian = required(custodian, "custodian");
        preservationStatus = preservationStatus == null ? EvidencePreservationStatus.UNVERIFIED : preservationStatus;
        issueIds = issueIds == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(issueIds));
    }

    public MatterEvidence associateIssue(String issueId) {
        String normalized = required(issueId, "issueId");
        Set<String> next = new LinkedHashSet<>(issueIds);
        next.add(normalized);
        return new MatterEvidence(id, tenantId, matterId, version + 1, objectReference, sha256,
                sourceType, acquiredAt, custodian, preservationStatus, next);
    }

    public MatterEvidence markPreserved(String actorId) {
        required(actorId, "actorId");
        if (preservationStatus == EvidencePreservationStatus.RELEASED) {
            throw new IllegalStateException("RELEASED_EVIDENCE_CANNOT_BE_PRESERVED");
        }
        return new MatterEvidence(id, tenantId, matterId, version + 1, objectReference, sha256,
                sourceType, acquiredAt, actorId, EvidencePreservationStatus.PRESERVED, issueIds);
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
