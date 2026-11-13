package cn.apple.test.domain;

import cn.apple.application.billing.BillingApplicationService;
import cn.apple.application.billing.InMemoryBillingRepository;
import cn.apple.domain.billing.BillingMetric;
import cn.apple.domain.billing.ProductPlan;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class BillingApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-25T02:00:00Z");

    @Test
    public void seedsCommercialPlansAndRequiresSubscriptionBeforeMetering() {
        BillingApplicationService service = service();
        assertEquals(4, service.plans().size());
        assertTrue(!service.quota("tenant-a", BillingMetric.WORKFLOW_RUNS, 1).allowed());
        assertThrows(IllegalStateException.class, () -> service.meter(new BillingApplicationService.MeteringRequest(
                "tenant-a", null, null, BillingMetric.WORKFLOW_RUNS, 1, "run", "unsubscribed", NOW)));
    }

    @Test
    public void enforcesPlanQuotaPerTenant() {
        BillingApplicationService service = service();
        service.subscribe("tenant-a", "TRIAL", true);
        BillingApplicationService.MeteringResult first = service.meter(new BillingApplicationService.MeteringRequest(
                "tenant-a", "matter-1", "run-1", BillingMetric.WORKFLOW_RUNS, 100, "run", "idem-1", NOW));
        assertTrue(first.quota().allowed());
        assertEquals(100L, service.usage("tenant-a").totals().get(BillingMetric.WORKFLOW_RUNS).longValue());
        assertThrows(IllegalStateException.class, () -> service.meter(new BillingApplicationService.MeteringRequest(
                "tenant-a", "matter-1", "run-2", BillingMetric.WORKFLOW_RUNS, 1, "run", "idem-2", NOW)));
        assertTrue(service.quota("tenant-b", BillingMetric.WORKFLOW_RUNS, 0).allowed() == false);
    }

    @Test
    public void usageIdempotencyIsTenantScopedAndConflictSafe() {
        BillingApplicationService service = service();
        service.subscribe("tenant-a", "TEAM", false);
        BillingApplicationService.MeteringRequest request = new BillingApplicationService.MeteringRequest(
                "tenant-a", "matter-1", "run-1", BillingMetric.API_CALLS, 2, "call", "same-key", NOW);
        BillingApplicationService.MeteringResult first = service.meter(request);
        BillingApplicationService.MeteringResult replay = service.meter(request);
        assertTrue(replay.idempotentReplay());
        assertEquals(first.entry(), replay.entry());
        assertThrows(IllegalStateException.class, () -> service.meter(new BillingApplicationService.MeteringRequest(
                "tenant-a", "matter-1", "run-1", BillingMetric.API_CALLS, 3, "call", "same-key", NOW)));
        service.subscribe("tenant-b", "TEAM", false);
        service.meter(new BillingApplicationService.MeteringRequest(
                "tenant-b", "matter-1", "run-1", BillingMetric.API_CALLS, 3, "call", "same-key", NOW));
        assertEquals(2L, service.usage("tenant-a").totals().get(BillingMetric.API_CALLS).longValue());
    }

    @Test
    public void idempotencyKeyCannotReplayDifferentMatterWorkflowOrEventTime() {
        BillingApplicationService service = service();
        service.subscribe("tenant-a", "TEAM", false);
        BillingApplicationService.MeteringRequest original = new BillingApplicationService.MeteringRequest(
                "tenant-a", "matter-1", "run-1", BillingMetric.API_CALLS, 2, " call ", "full-key", NOW);
        var first = service.meter(original);
        assertTrue(service.meter(new BillingApplicationService.MeteringRequest(
                "tenant-a", "matter-1", "run-1", BillingMetric.API_CALLS, 2, "call", "full-key", NOW)).idempotentReplay());
        assertThrows(IllegalStateException.class, () -> service.meter(new BillingApplicationService.MeteringRequest(
                "tenant-a", "matter-2", "run-1", BillingMetric.API_CALLS, 2, "call", "full-key", NOW)));
        assertThrows(IllegalStateException.class, () -> service.meter(new BillingApplicationService.MeteringRequest(
                "tenant-a", "matter-1", "run-2", BillingMetric.API_CALLS, 2, "call", "full-key", NOW)));
        assertThrows(IllegalStateException.class, () -> service.meter(new BillingApplicationService.MeteringRequest(
                "tenant-a", "matter-1", "run-1", BillingMetric.API_CALLS, 2, "call", "full-key", NOW.plusSeconds(1))));
        assertEquals(first.entry(), service.meter(new BillingApplicationService.MeteringRequest(
                "tenant-a", "matter-1", "run-1", BillingMetric.API_CALLS, 2, "call", "full-key", null)).entry());
        assertEquals(2L, service.usage("tenant-a").totals().get(BillingMetric.API_CALLS).longValue());
    }

    @Test
    public void rejectsMissingIdempotencyKeyBeforeRepositoryLookup() {
        BillingApplicationService service = service();
        assertThrows(IllegalArgumentException.class, () -> service.meter(new BillingApplicationService.MeteringRequest(
                "tenant-a", null, null, BillingMetric.API_CALLS, 1, "call", " ", NOW)));
    }

    @Test
    public void rejectsFutureUsageAndInvalidPlanLimits() {
        BillingApplicationService service = service();
        service.subscribe("tenant-a", "TRIAL", true);
        assertThrows(IllegalArgumentException.class, () -> service.meter(new BillingApplicationService.MeteringRequest(
                "tenant-a", null, null, BillingMetric.API_CALLS, 1, "call", "future", NOW.plusSeconds(61))));
        assertThrows(IllegalArgumentException.class, () -> new ProductPlan("bad", 0, "bad",
                cn.apple.domain.billing.BillingPeriod.NONE, "CNY", 0, 1, 1, 1, 1, 1, java.util.Set.of()));
    }

    private BillingApplicationService service() {
        return new BillingApplicationService(new InMemoryBillingRepository(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
