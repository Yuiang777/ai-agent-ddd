package cn.apple.domain.billing;

/** Metered dimensions that can be constrained by a tenant entitlement. */
public enum BillingMetric {
    SEATS,
    ACTIVE_MATTERS,
    WORKFLOW_RUNS,
    MODEL_TOKENS,
    OCR_PAGES,
    STORAGE_BYTES,
    RETRIEVAL_CALLS,
    MULTIMODAL_CALLS,
    API_CALLS
}
