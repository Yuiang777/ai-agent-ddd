package cn.apple.domain.billing;

import java.util.Objects;

/** Tenant billing owner. Payment provider identifiers are intentionally opaque. */
public record BillingAccount(String tenantId,
                             String legalEntityName,
                             String currency,
                             String status,
                             String taxProfile) {
    public BillingAccount {
        tenantId = required(tenantId, "tenantId");
        legalEntityName = required(legalEntityName, "legalEntityName");
        currency = required(currency, "currency").toUpperCase();
        status = required(status, "status").toUpperCase();
        taxProfile = taxProfile == null ? "" : taxProfile.trim();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
