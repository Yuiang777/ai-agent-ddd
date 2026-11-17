package cn.apple.test.domain;

import cn.apple.api.BillingController;
import cn.apple.application.billing.BillingApplicationService;
import cn.apple.application.billing.InMemoryBillingRepository;
import cn.apple.config.TenantContext;
import cn.apple.domain.billing.BillingMetric;
import cn.apple.domain.legal.matter.MatterActorRole;
import org.junit.Test;

import java.time.Instant;
import static org.junit.Assert.*;

public class BillingControllerAuthorizationTest {
    private final InMemoryBillingRepository repository = new InMemoryBillingRepository();
    private final BillingApplicationService service = new BillingApplicationService(repository);
    private final BillingController controller = new BillingController(service);

    @Test public void clientCannotReadOrMutateTenantBilling() {
        try {
            TenantContext.set(new TenantContext.Principal("tenant-a", "client", MatterActorRole.CLIENT));
            assertThrows(SecurityException.class, controller::account);
            assertThrows(SecurityException.class, controller::usage);
            assertThrows(SecurityException.class, () -> controller.quota(BillingMetric.API_CALLS, 1));
            assertThrows(SecurityException.class, () -> controller.subscribe(new BillingController.SubscribeRequest("TRIAL", true)));
            assertThrows(SecurityException.class, () -> controller.meter(new BillingController.MeterUsageRequest(
                    "matter-a", "run-a", BillingMetric.API_CALLS, 5, "call", "client-key", Instant.now())));
            assertTrue(repository.activeSubscription("tenant-a", Instant.now()).isEmpty());
            assertTrue(service.usage("tenant-a").totals().isEmpty());
        } finally { TenantContext.clear(); }
    }

    @Test public void paralegalCanReadButCannotChangeCommercialSubscription() {
        try {
            TenantContext.set(new TenantContext.Principal("tenant-a", "staff", MatterActorRole.PARALEGAL));
            assertTrue(controller.account().success());
            assertTrue(controller.usage().success());
            assertTrue(controller.quota(BillingMetric.API_CALLS, 1).success());
            assertThrows(SecurityException.class, () -> controller.subscribe(new BillingController.SubscribeRequest("TRIAL", true)));
            assertTrue(repository.activeSubscription("tenant-a", Instant.now()).isEmpty());
        } finally { TenantContext.clear(); }
    }

    @Test public void lawyerManagesSubscriptionAndSystemAloneCanRecordUsage() {
        try {
            TenantContext.set(new TenantContext.Principal("tenant-a", "lawyer", MatterActorRole.LAWYER));
            assertTrue(controller.subscribe(new BillingController.SubscribeRequest("TEAM", false)).success());
            assertThrows(SecurityException.class, () -> controller.meter(new BillingController.MeterUsageRequest(
                    "matter-a", "run-a", BillingMetric.API_CALLS, 2, "call", "lawyer-key", Instant.now())));
            TenantContext.set(new TenantContext.Principal("tenant-a", "metering-worker", MatterActorRole.SYSTEM));
            assertTrue(controller.meter(new BillingController.MeterUsageRequest(
                    "matter-a", "run-a", BillingMetric.API_CALLS, 2, "call", "system-key", Instant.now())).success());
            assertThrows(SecurityException.class, controller::usage);
            assertEquals(2L, service.usage("tenant-a").totals().get(BillingMetric.API_CALLS).longValue());
        } finally { TenantContext.clear(); }
    }

    @Test public void missingOrRolelessPrincipalFailsClosed() {
        TenantContext.clear();
        assertThrows(SecurityException.class, controller::account);
        try {
            TenantContext.set(new TenantContext.Principal("tenant-a", "unknown-role", null));
            assertThrows(SecurityException.class, controller::account);
            assertThrows(SecurityException.class, () -> controller.subscribe(new BillingController.SubscribeRequest("TRIAL", true)));
        } finally { TenantContext.clear(); }
    }
}
