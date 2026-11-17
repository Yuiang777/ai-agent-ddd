package cn.apple.api;

import cn.apple.application.billing.BillingApplicationService;
import cn.apple.domain.billing.BillingMetric;
import cn.apple.domain.billing.ProductPlan;
import cn.apple.domain.billing.Subscription;
import cn.apple.domain.legal.matter.MatterActorRole;
import cn.apple.config.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Tenant-scoped commercial API. Payment remains an explicit offline mock until a provider is configured. */
@RestController
@RequestMapping("/api/billing")
public class BillingController {
    private final BillingApplicationService service;

    public BillingController(BillingApplicationService service) {
        this.service = service;
    }

    @GetMapping("/plans")
    public ApiResponse<List<ProductPlan>> plans() { return ApiResponse.ok(service.plans()); }

    @GetMapping("/account")
    public ApiResponse<cn.apple.domain.billing.BillingAccount> account() {
        return ApiResponse.ok(service.account(internalTenant()));
    }

    @PostMapping("/subscriptions")
    public ApiResponse<Subscription> subscribe(
            @RequestBody SubscribeRequest request) {
        return ApiResponse.ok(service.subscribe(lawyerTenant(), request.planCode(), request.trial()));
    }

    @GetMapping("/usage")
    public ApiResponse<BillingApplicationService.UsageSummary> usage(
            ) {
        return ApiResponse.ok(service.usage(internalTenant()));
    }

    @PostMapping("/usage")
    public ApiResponse<BillingApplicationService.MeteringResult> meter(
            @RequestBody MeterUsageRequest request) {
        BillingApplicationService.MeteringRequest metering = new BillingApplicationService.MeteringRequest(
                systemTenant(), request.matterId(), request.workflowRunId(), request.metric(),
                request.quantity(), request.unit(), request.idempotencyKey(), request.occurredAt());
        return ApiResponse.ok(service.meter(metering));
    }

    @GetMapping("/quota/{metric}")
    public ApiResponse<BillingApplicationService.QuotaDecision> quota(
            @PathVariable BillingMetric metric,
            @RequestParam(defaultValue = "0") long requested) {
        return ApiResponse.ok(service.quota(internalTenant(), metric, requested));
    }

    private String lawyerTenant() {
        TenantContext.Principal principal = authenticatedPrincipal();
        if (principal.role() != MatterActorRole.LAWYER) {
            throw new SecurityException("BILLING_ADMINISTRATOR_REQUIRED");
        }
        return principal.tenantId();
    }

    private String internalTenant() {
        TenantContext.Principal principal = authenticatedPrincipal();
        if (principal.role() != cn.apple.domain.legal.matter.MatterActorRole.LAWYER
                && principal.role() != MatterActorRole.PARALEGAL) {
            throw new SecurityException("INTERNAL_BILLING_ACCESS_REQUIRED");
        }
        return principal.tenantId();
    }

    private String systemTenant() {
        TenantContext.Principal principal = authenticatedPrincipal();
        if (principal.role() != MatterActorRole.SYSTEM) {
            throw new SecurityException("SYSTEM_METERING_ROLE_REQUIRED");
        }
        return principal.tenantId();
    }

    private TenantContext.Principal authenticatedPrincipal() {
        TenantContext.Principal principal = TenantContext.current();
        if (principal == null) throw new SecurityException("AUTHENTICATED_TENANT_CONTEXT_REQUIRED");
        if (principal.role() == null) throw new SecurityException("AUTHENTICATED_ACTOR_ROLE_REQUIRED");
        return principal;
    }

    public record SubscribeRequest(String planCode, boolean trial) {}
    public record MeterUsageRequest(String matterId, String workflowRunId, BillingMetric metric,
                                    long quantity, String unit, String idempotencyKey,
                                    java.time.Instant occurredAt) {}
}
