package cn.apple.api;

import cn.apple.application.governance.GovernanceAuditService;
import cn.apple.application.governance.EnterpriseReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/governance")
@RequiredArgsConstructor
public class GovernanceController {

    private final GovernanceAuditService governanceAuditService;
    private final EnterpriseReadinessService enterpriseReadinessService;

    @GetMapping("/overview")
    public ApiResponse<GovernanceOverview> overview() {
        GovernanceAuditService.GovernanceMetrics metrics = governanceAuditService.metrics();
        return ApiResponse.ok(new GovernanceOverview(
                metrics.totalRequests(),
                metrics.successRequests(),
                metrics.failedRequests(),
                metrics.rateLimitedRequests(),
                metrics.avgCostMs(),
                metrics.activeClients()
        ));
    }

    @GetMapping("/events")
    public ApiResponse<List<GovernanceAuditService.AuditEvent>> events() {
        return ApiResponse.ok(governanceAuditService.recentEvents());
    }

    @GetMapping("/health")
    public ApiResponse<GovernanceHealth> health() {
        GovernanceAuditService.GovernanceMetrics metrics = governanceAuditService.metrics();
        String status = metrics.failedRequests() > metrics.successRequests() ? "degraded" : "ok";
        return ApiResponse.ok(new GovernanceHealth(status, metrics.activeClients(), metrics.rateLimitedRequests()));
    }

    @GetMapping("/readiness")
    public ApiResponse<EnterpriseReadinessService.ReadinessReport> readiness() {
        return ApiResponse.ok(enterpriseReadinessService.report());
    }

    public record GovernanceOverview(long totalRequests,
                                     long successRequests,
                                     long failedRequests,
                                     long rateLimitedRequests,
                                     long avgCostMs,
                                     int activeClients) {
    }

    public record GovernanceHealth(String status,
                                   int activeClients,
                                   long rateLimitedRequests) {
    }
}
