package cn.apple.application.billing;

import cn.apple.domain.billing.BillingAccount;
import cn.apple.domain.billing.BillingSubscriptionStatus;
import cn.apple.domain.billing.ProductPlan;
import cn.apple.domain.billing.Subscription;
import cn.apple.domain.billing.UsageLedgerEntry;
import cn.apple.domain.billing.repository.BillingRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Offline adapter. It is deliberately replaceable by a transactional SQL/outbox adapter. */
@Repository
@Profile("!test-sql")
public class InMemoryBillingRepository implements BillingRepository {
    private final ConcurrentMap<String, BillingAccount> accounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ProductPlan> plans = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UsageLedgerEntry> usage = new ConcurrentHashMap<>();

    public InMemoryBillingRepository() {
        seedPlans();
    }

    @Override
    public Optional<BillingAccount> findAccount(String tenantId) {
        return Optional.ofNullable(accounts.get(required(tenantId)));
    }

    @Override
    public BillingAccount saveAccount(BillingAccount account) {
        accounts.put(required(account.tenantId()), account);
        return account;
    }

    @Override
    public List<ProductPlan> listPlans() {
        return plans.values().stream().sorted(Comparator.comparing(ProductPlan::planCode)).toList();
    }

    @Override
    public Optional<ProductPlan> findPlan(String planCode, int version) {
        return Optional.ofNullable(plans.get(key(planCode, version)));
    }

    @Override
    public Optional<Subscription> activeSubscription(String tenantId, Instant at) {
        return subscriptions.values().stream()
                .filter(item -> item.tenantId().equals(required(tenantId)) && item.activeAt(at))
                .max(Comparator.comparing(Subscription::effectiveFrom));
    }

    @Override
    public Subscription saveSubscription(Subscription subscription) {
        subscriptions.put(required(subscription.id()), subscription);
        return subscription;
    }

    @Override
    public Optional<UsageLedgerEntry> findUsageByIdempotencyKey(String tenantId, String idempotencyKey) {
        return Optional.ofNullable(usage.get(usageKey(tenantId, idempotencyKey)));
    }

    @Override
    public UsageLedgerEntry appendUsage(UsageLedgerEntry entry) {
        String key = usageKey(entry.tenantId(), entry.idempotencyKey());
        UsageLedgerEntry previous = usage.putIfAbsent(key, entry);
        if (previous != null && !previous.equals(entry)) {
            throw new IllegalStateException("USAGE_IDEMPOTENCY_CONFLICT");
        }
        return previous == null ? entry : previous;
    }

    @Override
    public List<UsageLedgerEntry> usage(String tenantId, Instant fromInclusive, Instant toExclusive) {
        String checked = required(tenantId);
        return usage.values().stream()
                .filter(item -> item.tenantId().equals(checked)
                        && !item.occurredAt().isBefore(fromInclusive)
                        && item.occurredAt().isBefore(toExclusive))
                .sorted(Comparator.comparing(UsageLedgerEntry::occurredAt))
                .toList();
    }

    private void seedPlans() {
        put(new ProductPlan("TRIAL", 1, "试用版", cn.apple.domain.billing.BillingPeriod.NONE,
                "CNY", 0, 3, 10, 1_000_000_000L, 100, 100_000,
                java.util.Set.of("legal-workflow", "knowledge-search")));
        put(new ProductPlan("TEAM", 1, "团队版", cn.apple.domain.billing.BillingPeriod.MONTHLY,
                "CNY", 1_999_00L, 10, 100, 10_000_000_000L, 2_000, 5_000_000,
                java.util.Set.of("legal-workflow", "knowledge-search", "matter-acl")));
        put(new ProductPlan("PROFESSIONAL", 1, "专业版", cn.apple.domain.billing.BillingPeriod.MONTHLY,
                "CNY", 5_999_00L, 50, 500, 50_000_000_000L, 10_000, 50_000_000,
                java.util.Set.of("legal-workflow", "knowledge-search", "matter-acl", "advanced-models")));
        put(new ProductPlan("ENTERPRISE", 1, "企业版", cn.apple.domain.billing.BillingPeriod.YEARLY,
                "CNY", 0, -1, -1, -1, -1, -1,
                java.util.Set.of("legal-workflow", "knowledge-search", "matter-acl", "advanced-models", "sso", "audit-export")));
    }

    private void put(ProductPlan plan) { plans.put(key(plan.planCode(), plan.version()), plan); }
    private String key(String code, int version) { return required(code).toUpperCase() + ":" + version; }
    private String usageKey(String tenantId, String idempotencyKey) { return required(tenantId) + ":" + required(idempotencyKey); }
    private String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("billing identifier is required");
        return value.trim();
    }
}
