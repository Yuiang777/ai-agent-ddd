package cn.apple.domain.billing;

import java.time.Instant;
import java.util.Objects;

public record Subscription(String id,
                           String tenantId,
                           String planCode,
                           BillingSubscriptionStatus status,
                           Instant effectiveFrom,
                           Instant effectiveTo,
                           Instant trialEndAt,
                           String externalSubscriptionId) {
    public Subscription {
        id = required(id, "id");
        tenantId = required(tenantId, "tenantId");
        planCode = required(planCode, "planCode").toUpperCase();
        status = Objects.requireNonNull(status, "status");
        effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must not precede effectiveFrom");
        }
        externalSubscriptionId = externalSubscriptionId == null ? "" : externalSubscriptionId.trim();
    }

    public boolean activeAt(Instant at) {
        Instant checked = Objects.requireNonNull(at, "at");
        boolean statusActive = status == BillingSubscriptionStatus.ACTIVE || status == BillingSubscriptionStatus.TRIALING;
        return statusActive && !checked.isBefore(effectiveFrom)
                && (effectiveTo == null || checked.isBefore(effectiveTo));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
