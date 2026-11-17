package cn.apple.domain.billing;

/** Billing cadence. NONE is used by trials and non-recurring offers. */
public enum BillingPeriod {
    NONE,
    MONTHLY,
    YEARLY
}
