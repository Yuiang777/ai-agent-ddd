package cn.apple.domain.legal.matter;

import java.time.Instant;
import java.time.ZoneId;

/** A deadline is actionable only after a human verifies its authoritative source. */
public record MatterDeadline(String id,
                             String tenantId,
                             String matterId,
                             Instant dueAt,
                             ZoneId zoneId,
                             DeadlineBasis basis,
                             String basisReference,
                             DeadlineStatus status,
                             String verifiedBy,
                             Instant verifiedAt) {
    public MatterDeadline {
        id = required(id, "id");
        tenantId = required(tenantId, "tenantId");
        matterId = required(matterId, "matterId");
        dueAt = dueAt == null ? Instant.now() : dueAt;
        zoneId = zoneId == null ? ZoneId.of("Asia/Shanghai") : zoneId;
        basis = basis == null ? DeadlineBasis.INTERNAL_TARGET : basis;
        basisReference = basisReference == null ? "" : basisReference.trim();
        status = status == null ? DeadlineStatus.PROPOSED : status;
        verifiedBy = verifiedBy == null ? "" : verifiedBy.trim();
    }

    public MatterDeadline verify(String reviewerId, Instant at) {
        if (status != DeadlineStatus.PROPOSED) throw new IllegalStateException("DEADLINE_NOT_PROPOSED");
        if (basisReference.isBlank()) throw new IllegalStateException("DEADLINE_SOURCE_REQUIRED");
        String reviewer = required(reviewerId, "reviewerId");
        return new MatterDeadline(id, tenantId, matterId, dueAt, zoneId, basis, basisReference,
                DeadlineStatus.VERIFIED, reviewer, at == null ? Instant.now() : at);
    }

    public boolean actionable() {
        return status == DeadlineStatus.VERIFIED;
    }

    public boolean overdueAt(Instant now) {
        return now != null && now.isAfter(dueAt)
                && status != DeadlineStatus.COMPLETED && status != DeadlineStatus.WAIVED;
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
