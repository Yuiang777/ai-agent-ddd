package cn.apple.domain.billing;

import java.util.Objects;
import java.util.Set;

/** Versioned product entitlement snapshot. Limits are inclusive monthly quantities; -1 means unlimited. */
public record ProductPlan(String planCode,
                          int version,
                          String displayName,
                          BillingPeriod billingPeriod,
                          String currency,
                          long priceMinor,
                          long seatLimit,
                          long matterLimit,
                          long storageBytes,
                          long monthlyWorkflowRuns,
                          long monthlyModelTokens,
                          Set<String> features) {
    public ProductPlan {
        planCode = required(planCode, "planCode").toUpperCase();
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        displayName = required(displayName, "displayName");
        billingPeriod = Objects.requireNonNull(billingPeriod, "billingPeriod");
        currency = required(currency, "currency").toUpperCase();
        if (priceMinor < 0) throw new IllegalArgumentException("priceMinor must not be negative");
        validateLimit(seatLimit, "seatLimit");
        validateLimit(matterLimit, "matterLimit");
        validateLimit(storageBytes, "storageBytes");
        validateLimit(monthlyWorkflowRuns, "monthlyWorkflowRuns");
        validateLimit(monthlyModelTokens, "monthlyModelTokens");
        features = features == null ? Set.of() : Set.copyOf(features);
    }

    public long limit(BillingMetric metric) {
        return switch (Objects.requireNonNull(metric, "metric")) {
            case SEATS -> seatLimit;
            case ACTIVE_MATTERS -> matterLimit;
            case STORAGE_BYTES -> storageBytes;
            case WORKFLOW_RUNS -> monthlyWorkflowRuns;
            case MODEL_TOKENS -> monthlyModelTokens;
            case OCR_PAGES, RETRIEVAL_CALLS, MULTIMODAL_CALLS, API_CALLS -> -1;
        };
    }

    public boolean includes(String feature) {
        return feature != null && features.contains(feature.trim());
    }

    private static void validateLimit(long value, String field) {
        if (value < -1) throw new IllegalArgumentException(field + " must be -1 or non-negative");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
