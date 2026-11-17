package cn.apple.application.governance;

import lombok.extern.slf4j.Slf4j;
import cn.apple.config.TenantContext;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class GovernanceAuditService {

    private static final int MAX_EVENTS = 200;
    private static final int RATE_LIMIT_PER_MINUTE = 30;
    private static final long RATE_WINDOW_MS = 60_000L;
    private static final int MAX_TRACKED_REQUEST_SCOPES = 10_000;

    private final Deque<AuditEvent> events = new ArrayDeque<>();
    private final Map<RequestScope, WindowCounter> requestWindows = new ConcurrentHashMap<>();
    private final Object scopeCreationMonitor = new Object();
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong successRequests = new AtomicLong();
    private final AtomicLong failedRequests = new AtomicLong();
    private final AtomicLong totalCostMs = new AtomicLong();
    private final AtomicLong rateLimitedRequests = new AtomicLong();

    public boolean allowRequest(String clientId) {
        TenantContext.Principal principal = TenantContext.current();
        RequestScope scope = new RequestScope(clientId,
                principal == null ? null : principal.tenantId(),
                principal == null ? null : principal.actorId());
        for (;;) {
            long now = System.currentTimeMillis();
            WindowCounter counter = requestWindows.get(scope);
            if (counter == null) {
                synchronized (scopeCreationMonitor) {
                    counter = requestWindows.get(scope);
                    if (counter == null) {
                        if (requestWindows.size() >= MAX_TRACKED_REQUEST_SCOPES) {
                            evictExpiredCounters(now);
                        }
                        if (requestWindows.size() >= MAX_TRACKED_REQUEST_SCOPES) {
                            rateLimitedRequests.incrementAndGet();
                            return false;
                        }
                        counter = new WindowCounter(now);
                        requestWindows.put(scope, counter);
                    }
                }
            }
            synchronized (counter) {
                // Eviction may have detached this bucket after lookup. Never count on it.
                if (requestWindows.get(scope) != counter) {
                    continue;
                }
                now = System.currentTimeMillis();
                if (now - counter.windowStartMs >= RATE_WINDOW_MS) {
                    counter.windowStartMs = now;
                    counter.count = 0;
                }
                if (counter.count >= RATE_LIMIT_PER_MINUTE) {
                    rateLimitedRequests.incrementAndGet();
                    return false;
                }
                counter.count++;
                return true;
            }
        }
    }

    private void evictExpiredCounters(long now) {
        requestWindows.forEach((scope, counter) -> {
            synchronized (counter) {
                if (now - counter.windowStartMs >= RATE_WINDOW_MS * 2) {
                    requestWindows.remove(scope, counter);
                }
            }
        });
    }
    public void recordChat(String clientId, String message, boolean success, long costMs, String detail) {
        totalRequests.incrementAndGet();
        if (success) {
            successRequests.incrementAndGet();
        } else {
            failedRequests.incrementAndGet();
        }
        totalCostMs.addAndGet(Math.max(costMs, 0));

        AuditEvent event = new AuditEvent(
                LocalDateTime.now(),
                clientId,
                message == null ? 0 : message.length(),
                success,
                costMs,
                detail
        );
        synchronized (events) {
            events.addFirst(event);
            while (events.size() > MAX_EVENTS) {
                events.removeLast();
            }
        }
        log.info("governance audit, clientId={}, success={}, costMs={}, detail={}", clientId, success, costMs, detail);
    }

    public List<AuditEvent> recentEvents() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    public GovernanceMetrics metrics() {
        long total = totalRequests.get();
        long success = successRequests.get();
        long failed = failedRequests.get();
        long avgCostMs = total == 0 ? 0 : totalCostMs.get() / total;
        int activeClients = (int) requestWindows.keySet().stream().map(RequestScope::clientId).distinct().count();
        return new GovernanceMetrics(total, success, failed, rateLimitedRequests.get(), avgCostMs, activeClients);
    }

    public record AuditEvent(LocalDateTime timestamp,
                             String clientId,
                             int messageLength,
                             boolean success,
                             long costMs,
                             String detail) {
    }

    public record GovernanceMetrics(long totalRequests,
                                    long successRequests,
                                    long failedRequests,
                                    long rateLimitedRequests,
                                    long avgCostMs,
                                    int activeClients) {
    }

    // A tuple avoids delimiter collisions and distinguishes offline from authenticated identities.
    private record RequestScope(String clientId, String tenantId, String actorId) { }

    private static final class WindowCounter {
        private volatile long windowStartMs;
        private int count;

        private WindowCounter(long windowStartMs) {
            this.windowStartMs = windowStartMs;
        }
    }
}
