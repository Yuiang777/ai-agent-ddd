package cn.apple.domain.billing;

import java.time.Instant;
import java.util.Objects;

/** Immutable usage event. idempotencyKey is unique inside a tenant. */
public record UsageLedgerEntry(String tenantId,
                               String matterId,
                               String workflowRunId,
                               BillingMetric metric,
                               long quantity,
                               String unit,
                               String idempotencyKey,
                               Instant occurredAt) {
    public UsageLedgerEntry {
        tenantId = required(tenantId, "tenantId");
        matterId = optional(matterId);
        workflowRunId = optional(workflowRunId);
        metric = Objects.requireNonNull(metric, "metric");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        unit = required(unit, "unit");
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }
}
