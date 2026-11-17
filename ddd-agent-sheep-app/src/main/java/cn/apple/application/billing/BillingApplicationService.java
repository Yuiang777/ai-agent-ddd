package cn.apple.application.billing;

import cn.apple.domain.billing.BillingAccount;
import cn.apple.domain.billing.BillingMetric;
import cn.apple.domain.billing.BillingPeriod;
import cn.apple.domain.billing.BillingSubscriptionStatus;
import cn.apple.domain.billing.ProductPlan;
import cn.apple.domain.billing.Subscription;
import cn.apple.domain.billing.UsageLedgerEntry;
import cn.apple.domain.billing.repository.BillingRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Application boundary for entitlement, metering and offline billing simulation. */
@Service
public class BillingApplicationService {
    private final BillingRepository repository;
    private final Clock clock;
    private final ConcurrentMap<String, Object> tenantLocks = new ConcurrentHashMap<>();

    @Autowired
    public BillingApplicationService(BillingRepository repository) {
        this(repository, Clock.systemUTC());
    }

    public BillingApplicationService(BillingRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<ProductPlan> plans() {
        return repository.listPlans();
    }

    public BillingAccount account(String tenantId) {
        return repository.findAccount(requiredTenant(tenantId))
                .orElseGet(() -> repository.saveAccount(new BillingAccount(requiredTenant(tenantId),
                        "未设置企业名称", "CNY", "ACTIVE", "")));
    }

    public Subscription subscribe(String tenantId, String planCode, boolean trial) {
        String checkedTenant = requiredTenant(tenantId);
        ProductPlan plan = plan(planCode, 1);
        Instant now = clock.instant();
        synchronized (tenantLocks.computeIfAbsent(checkedTenant, ignored -> new Object())) {
            repository.activeSubscription(checkedTenant, now).ifPresent(existing -> {
                throw new IllegalStateException("ACTIVE_SUBSCRIPTION_EXISTS");
            });
            Instant end = plan.billingPeriod() == BillingPeriod.MONTHLY ? now.plusSeconds(31L * 24 * 3600)
                    : plan.billingPeriod() == BillingPeriod.YEARLY ? now.plusSeconds(366L * 24 * 3600) : null;
            BillingSubscriptionStatus status = trial ? BillingSubscriptionStatus.TRIALING : BillingSubscriptionStatus.ACTIVE;
            Instant trialEnd = trial ? now.plusSeconds(14L * 24 * 3600) : null;
            return repository.saveSubscription(new Subscription(UUID.randomUUID().toString(), checkedTenant,
                    plan.planCode(), status, now, end, trialEnd, "offline:" + UUID.randomUUID()));
        }
    }

    /** Check entitlement and append a usage event atomically for this tenant. */
    public MeteringResult meter(MeteringRequest request) {
        if (request == null) throw new IllegalArgumentException("metering request is required");
        String tenantId = requiredTenant(request.tenantId());
        synchronized (tenantLocks.computeIfAbsent(tenantId, ignored -> new Object())) {
            if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
                throw new IllegalArgumentException("idempotencyKey is required");
            }
            Instant now = request.occurredAt() == null ? clock.instant() : request.occurredAt();
            UsageLedgerEntry duplicate = repository.findUsageByIdempotencyKey(tenantId, request.idempotencyKey()).orElse(null);
            if (duplicate != null) {
                if (!sameMeteringRequest(duplicate, request)) throw new IllegalStateException("USAGE_IDEMPOTENCY_CONFLICT");
                return result(duplicate, true, quota(tenantId, duplicate.metric(), 0));
            }
            if (request.unit() == null || request.unit().isBlank()) throw new IllegalArgumentException("unit is required");
            if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) throw new IllegalArgumentException("idempotencyKey is required");
            if (now.isAfter(clock.instant().plusSeconds(60))) throw new IllegalArgumentException("occurredAt is in the future");
            QuotaDecision decision = quota(tenantId, request.metric(), request.quantity(), now);
            if ("NONE".equals(decision.planCode())) throw new IllegalStateException("ACTIVE_SUBSCRIPTION_REQUIRED");
            if (!decision.allowed()) throw new IllegalStateException("QUOTA_EXCEEDED:" + request.metric());
            UsageLedgerEntry entry = new UsageLedgerEntry(tenantId, request.matterId(), request.workflowRunId(),
                    request.metric(), request.quantity(), request.unit(), request.idempotencyKey(), now);
            UsageLedgerEntry saved = repository.appendUsage(entry);
            return result(saved, false, decision);
        }
    }

    public QuotaDecision quota(String tenantId, BillingMetric metric, long requested) {
        return quota(tenantId, metric, requested, clock.instant());
    }

    public UsageSummary usage(String tenantId) {
        String checked = requiredTenant(tenantId);
        Period period = period(clock.instant());
        Map<BillingMetric, Long> totals = new java.util.EnumMap<>(BillingMetric.class);
        repository.usage(checked, period.from(), period.to()).forEach(item ->
                totals.merge(item.metric(), item.quantity(), Long::sum));
        return new UsageSummary(checked, period.from(), period.to(), Map.copyOf(totals));
    }

    private QuotaDecision quota(String tenantId, BillingMetric metric, long requested, Instant at) {
        if (metric == null) throw new IllegalArgumentException("metric is required");
        if (requested < 0) throw new IllegalArgumentException("requested must not be negative");
        Subscription subscription = repository.activeSubscription(requiredTenant(tenantId), at).orElse(null);
        if (subscription == null) {
            return new QuotaDecision(requiredTenant(tenantId), metric, 0, requested, 0, false, "NONE");
        }
        ProductPlan plan = plan(subscription.planCode(), 1);
        long limit = plan.limit(metric);
        long used = repository.usage(subscription.tenantId(), period(at).from(), period(at).to()).stream()
                .filter(item -> item.metric() == metric).mapToLong(UsageLedgerEntry::quantity).sum();
        boolean allowed = limit < 0 || used <= limit - requested;
        return new QuotaDecision(subscription.tenantId(), metric, used, requested, limit, allowed, plan.planCode());
    }

    private boolean sameMeteringRequest(UsageLedgerEntry existing, MeteringRequest request) {
        return existing.metric() == request.metric()
                && existing.quantity() == request.quantity()
                && existing.unit().equals(request.unit() == null ? "" : request.unit().trim())
                && existing.matterId().equals(request.matterId() == null ? "" : request.matterId().trim())
                && existing.workflowRunId().equals(request.workflowRunId() == null ? "" : request.workflowRunId().trim())
                && (request.occurredAt() == null || existing.occurredAt().equals(request.occurredAt()));
    }

    private MeteringResult result(UsageLedgerEntry entry, boolean duplicate, QuotaDecision decision) {
        return new MeteringResult(entry, duplicate, decision);
    }

    private ProductPlan plan(String code, int version) {
        return repository.findPlan(code, version).orElseThrow(() -> new IllegalArgumentException("PLAN_NOT_FOUND"));
    }

    private Period period(Instant at) {
        YearMonth month = YearMonth.from(at.atZone(ZoneOffset.UTC));
        return new Period(month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private String requiredTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
        return tenantId.trim();
    }

    private record Period(Instant from, Instant to) {}

    public record MeteringRequest(String tenantId, String matterId, String workflowRunId, BillingMetric metric,
                                  long quantity, String unit, String idempotencyKey, Instant occurredAt) {}

    public record QuotaDecision(String tenantId, BillingMetric metric, long used, long requested, long limit,
                                boolean allowed, String planCode) {}

    public record MeteringResult(UsageLedgerEntry entry, boolean idempotentReplay, QuotaDecision quota) {}

    public record UsageSummary(String tenantId, Instant periodFrom, Instant periodTo, Map<BillingMetric, Long> totals) {}
}
