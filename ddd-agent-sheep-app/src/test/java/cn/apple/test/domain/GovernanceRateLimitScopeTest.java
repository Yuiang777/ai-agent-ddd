package cn.apple.test.domain;

import cn.apple.application.governance.GovernanceAuditService;
import cn.apple.config.TenantContext;
import cn.apple.domain.legal.matter.MatterActorRole;
import org.junit.Test;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class GovernanceRateLimitScopeTest {
    @Test
    public void concurrentNewScopesCannotExceedCapacityAndExistingUsersRemainUsable() throws Exception {
        GovernanceAuditService service = new GovernanceAuditService();
        TenantContext.clear();
        for (int i = 0; i < 9999; i++) assertTrue(service.allowRequest("route-" + i));
        ExecutorService workers = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 40; i++) {
                String route = "new-route-" + i;
                results.add(workers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return service.allowRequest(route);
                }));
            }
            start.countDown();
            int allowed = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) allowed++;
            assertEquals("Only one capacity slot remains", 1, allowed);
            assertEquals(10000, service.metrics().activeClients());
            assertTrue("Capacity limits must not reject existing scopes", service.allowRequest("route-0"));
            assertEquals(39, service.metrics().rateLimitedRequests());
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }
    @Test
    public void fullCapacityReclaimsExpiredBucketsWithoutResettingActiveQuota() throws Exception {
        GovernanceAuditService service = new GovernanceAuditService();
        TenantContext.clear();
        for (int i = 0; i < 30; i++) assertTrue(service.allowRequest("active"));
        for (int i = 0; i < 9999; i++) assertTrue(service.allowRequest("expired-" + i));
        var windowsField = GovernanceAuditService.class.getDeclaredField("requestWindows");
        windowsField.setAccessible(true);
        var windows = (java.util.Map<?, ?>) windowsField.get(service);
        // Age only the one-request buckets; avoid wall-clock sleeps in the regression.
        for (Object counter : windows.values()) {
            var count = counter.getClass().getDeclaredField("count");
            count.setAccessible(true);
            if (count.getInt(counter) == 1) {
                var started = counter.getClass().getDeclaredField("windowStartMs");
                started.setAccessible(true);
                started.setLong(counter, System.currentTimeMillis() - 180000L);
            }
        }
        assertTrue(service.allowRequest("new-user"));
        assertEquals(2, service.metrics().activeClients());
        assertFalse("Reclamation must preserve active user allowance", service.allowRequest("active"));
        assertTrue(service.allowRequest("expired-0"));
    }
    @Test
    public void requestWaitingOnDetachedBucketMustUseReplacementQuota() throws Exception {
        GovernanceAuditService service = new GovernanceAuditService();
        TenantContext.clear();
        assertTrue(service.allowRequest("same-route"));
        var field = GovernanceAuditService.class.getDeclaredField("requestWindows");
        field.setAccessible(true);
        var windows = (java.util.Map<?, ?>) field.get(service);
        var entry = windows.entrySet().iterator().next();
        Object scope = entry.getKey();
        Object oldCounter = entry.getValue();
        var result = new CompletableFuture<Boolean>();
        Thread requester = new Thread(() -> {
            try {
                result.complete(service.allowRequest("same-route"));
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        }, "detached-rate-bucket-request");
        try {
            synchronized (oldCounter) {
                requester.start();
                var threads = java.lang.management.ManagementFactory.getThreadMXBean();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waitingOnOldCounter = false;
                while (System.nanoTime() < deadline) {
                    var info = threads.getThreadInfo(requester.getId());
                    if (info != null && info.getThreadState() == Thread.State.BLOCKED
                            && info.getLockInfo() != null
                            && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(oldCounter)) {
                        waitingOnOldCounter = true;
                        break;
                    }
                    Thread.yield();
                }
                assertTrue("Requester must have read and be waiting on the old bucket", waitingOnOldCounter);
                // Simulate eviction while holding the exact monitor used by the real evictor.
                assertTrue(windows.remove(scope, oldCounter));
                for (int i = 0; i < 30; i++) assertTrue(service.allowRequest("same-route"));
                assertFalse(service.allowRequest("same-route"));
            }
            assertFalse("Waiting request must observe the replacement bucket's exhausted quota",
                    result.get(5, TimeUnit.SECONDS));
            assertEquals(1, service.metrics().activeClients());
        } finally {
            requester.join(5000);
            assertFalse("Request must not deadlock during bucket replacement", requester.isAlive());
        }
    }
    private void identity(String tenant, String actor) {
        TenantContext.set(new TenantContext.Principal(tenant, actor, MatterActorRole.LAWYER));
    }

    @Test
    public void oneUserCannotExhaustOtherUsersTenantsOrModelRoutes() {
        GovernanceAuditService service = new GovernanceAuditService();
        try {
            identity("tenant-a", "actor-a");
            for (int i = 0; i < 30; i++) assertTrue(service.allowRequest("shared-model"));
            assertFalse(service.allowRequest("shared-model"));
            assertTrue(service.allowRequest("other-model"));
            identity("tenant-a", "actor-b");
            assertTrue(service.allowRequest("shared-model"));
            identity("tenant-b", "actor-a");
            assertTrue(service.allowRequest("shared-model"));
            assertEquals("Active clients counts model routes, not users", 2, service.metrics().activeClients());
            identity("tenant-a", "actor-a");
            assertFalse(service.allowRequest("shared-model"));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    public void offlineScopeCannotCollideWithAuthenticatedIdentity() {
        GovernanceAuditService service = new GovernanceAuditService();
        TenantContext.clear();
        for (int i = 0; i < 30; i++) assertTrue(service.allowRequest("model"));
        assertFalse(service.allowRequest("model"));
        try {
            identity("offline-tenant", "offline-user");
            assertTrue(service.allowRequest("model"));
        } finally {
            TenantContext.clear();
        }
        assertFalse(service.allowRequest("model"));
    }

    @Test
    public void simultaneousRequestsCannotExceedPerIdentityAllowance() throws Exception {
        GovernanceAuditService service = new GovernanceAuditService();
        ExecutorService workers = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 60; i++) {
                results.add(workers.submit(() -> {
                    identity("tenant", "actor");
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        return service.allowRequest("model");
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            start.countDown();
            int allowed = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) allowed++;
            assertEquals(30, allowed);
            assertEquals(30, service.metrics().rateLimitedRequests());
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }
}
