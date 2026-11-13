package cn.apple.domain.billing.repository;

import cn.apple.domain.billing.BillingAccount;
import cn.apple.domain.billing.ProductPlan;
import cn.apple.domain.billing.Subscription;
import cn.apple.domain.billing.UsageLedgerEntry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port. SQL implementation must retain tenant predicates on every method. */
public interface BillingRepository {
    Optional<BillingAccount> findAccount(String tenantId);
    BillingAccount saveAccount(BillingAccount account);
    List<ProductPlan> listPlans();
    Optional<ProductPlan> findPlan(String planCode, int version);
    Optional<Subscription> activeSubscription(String tenantId, Instant at);
    Subscription saveSubscription(Subscription subscription);
    Optional<UsageLedgerEntry> findUsageByIdempotencyKey(String tenantId, String idempotencyKey);
    UsageLedgerEntry appendUsage(UsageLedgerEntry entry);
    List<UsageLedgerEntry> usage(String tenantId, Instant fromInclusive, Instant toExclusive);
}
