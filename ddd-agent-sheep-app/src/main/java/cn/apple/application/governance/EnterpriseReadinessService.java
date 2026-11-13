package cn.apple.application.governance;

import cn.apple.application.agent.AiClientRuntimeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EnterpriseReadinessService {

    private final Environment environment;
    private final AiClientRuntimeService aiClientRuntimeService;
    private final boolean infrastructureEnabled;
    private final boolean enterpriseSecurityContextEnabled;
    private final boolean automationReleaseEnabled;
    private final String matterStore;
    private final boolean billingEnabled;
    private final String billingProvider;

    @Autowired
    public EnterpriseReadinessService(Environment environment,
                                      AiClientRuntimeService aiClientRuntimeService,
                                      @Value("${app.infrastructure.enabled:false}") boolean infrastructureEnabled,
                                      @Value("${app.enterprise.security-context-enabled:false}") boolean enterpriseSecurityContextEnabled,
                                      @Value("${app.legal.automation-release-enabled:false}") boolean automationReleaseEnabled,
                                      @Value("${app.legal.matter-store:memory}") String matterStore,
                                      @Value("${app.billing.enabled:false}") boolean billingEnabled,
                                      @Value("${app.billing.provider:mock}") String billingProvider) {
        this.environment = environment;
        this.aiClientRuntimeService = aiClientRuntimeService;
        this.infrastructureEnabled = infrastructureEnabled;
        this.enterpriseSecurityContextEnabled = enterpriseSecurityContextEnabled;
        this.automationReleaseEnabled = automationReleaseEnabled;
        this.matterStore = matterStore == null ? "" : matterStore.trim();
        this.billingEnabled = billingEnabled;
        this.billingProvider = billingProvider == null || billingProvider.isBlank() ? "mock" : billingProvider.trim();
    }

    /** Backward-compatible constructor for offline unit tests and embedders. */
    public EnterpriseReadinessService(Environment environment,
                                      AiClientRuntimeService aiClientRuntimeService,
                                      boolean infrastructureEnabled,
                                      boolean enterpriseSecurityContextEnabled,
                                      boolean automationReleaseEnabled) {
        this(environment, aiClientRuntimeService, infrastructureEnabled,
                enterpriseSecurityContextEnabled, automationReleaseEnabled, "memory", false, "mock");
    }

    /** Backward-compatible constructor for embedders that specify the matter store. */
    public EnterpriseReadinessService(Environment environment,
                                      AiClientRuntimeService aiClientRuntimeService,
                                      boolean infrastructureEnabled,
                                      boolean enterpriseSecurityContextEnabled,
                                      boolean automationReleaseEnabled,
                                      String matterStore) {
        this(environment, aiClientRuntimeService, infrastructureEnabled,
                enterpriseSecurityContextEnabled, automationReleaseEnabled, matterStore, false, "mock");
    }

    public ReadinessReport report() {
        AiClientRuntimeService.RuntimeStatus model = aiClientRuntimeService.status();
        Map<String, CapabilityStatus> capabilities = new LinkedHashMap<>();
        capabilities.put("mysql_configuration", configuredCapability(infrastructureEnabled,
                infrastructureEnabled ? "configured but connection and migration health are not yet validated" : "disabled by app.infrastructure.enabled"));
        capabilities.put("pgvector_retrieval", configuredCapability(infrastructureEnabled,
                infrastructureEnabled ? "configured but vector schema, embedding health and keyword index are not yet validated" : "disabled; workflow will fail closed"));
        capabilities.put("model_runtime", configuredCapability(model.enabled(),
                model.enabled() ? "runtime policy is enabled but model reachability and evaluation gates are not yet validated" : "disabled by app.model-runtime.enabled"));
        capabilities.put("evidence_quality_gate", capability(true,
                "retrieval diagnostics and deterministic manual-review routing enabled"));
        capabilities.put("tenant_knowledge_isolation", new CapabilityStatus(
                enterpriseSecurityContextEnabled ? "CONFIGURED_UNVERIFIED" : "PLANNED",
                enterpriseSecurityContextEnabled
                        ? "security context flag is enabled; verify OIDC claims, matter ACLs, storage filters, cache keys and audit coverage"
                        : "retrieval accepts tenant filters, but authenticated tenant context and end-to-end enforcement are not connected"));
        capabilities.put("automation_release_gate", new CapabilityStatus(
                automationReleaseEnabled ? "ARMED_UNVERIFIED" : "DISABLED",
                automationReleaseEnabled
                        ? "explicit release flag is set; retrieval evaluation, legal review and incident rollback checks still apply"
                        : "disabled until an authorized production release process enables it"));
        capabilities.put("matter_lifecycle_store", new CapabilityStatus(
                infrastructureEnabled && !"sql".equalsIgnoreCase(matterStore) ? "PLANNED" :
                        infrastructureEnabled ? "CONFIGURED_UNVERIFIED" : "OFFLINE_MEMORY",
                infrastructureEnabled && !"sql".equalsIgnoreCase(matterStore)
                        ? "production infrastructure is enabled but no transactional matter repository is selected"
                        : infrastructureEnabled
                        ? "SQL matter adapter selected; verify schema migration, transaction rollback, tenant isolation and database recovery in the target MySQL environment"
                        : "offline matter memory is not durable; SQL persistence is not enabled"));
        capabilities.put("billing_and_entitlements", new CapabilityStatus(
                !billingEnabled ? "OFFLINE_MOCK" : "CONFIGURED_UNVERIFIED",
                !billingEnabled
                        ? "offline plans, tenant subscriptions, quota checks and idempotent usage ledger are available; payment, invoice and durable ledger are not enabled"
                        : "billing provider is configured as " + billingProvider + "; verify signed webhooks, tax, invoice persistence, reconciliation and entitlement cache invalidation"));

        boolean readyForAutomatedLegalReasoning = infrastructureEnabled
                && "sql".equalsIgnoreCase(matterStore)
                && model.enabled()
                && enterpriseSecurityContextEnabled
                && automationReleaseEnabled;
        List<String> actions = new java.util.ArrayList<>();
        if (!infrastructureEnabled) actions.add("Configure MySQL, pgvector and embedding credentials");
        if (!infrastructureEnabled) actions.add("Set APP_INFRASTRUCTURE_ENABLED=true only after middleware is reachable");
        if (!model.enabled()) actions.add("Configure the model client and then set APP_MODEL_RUNTIME_ENABLED=true");
        if (!enterpriseSecurityContextEnabled) actions.add("Connect OIDC/SSO and inject authenticated tenant and matter ACL context into every storage and retrieval path");
        if (!automationReleaseEnabled) actions.add("Complete retrieval evaluation, legal review approval and rollback checks before setting APP_LEGAL_AUTOMATION_RELEASE_ENABLED=true");
        if (infrastructureEnabled && !"sql".equalsIgnoreCase(matterStore)) actions.add("Select a tenant-aware transactional matter repository with APP_LEGAL_MATTER_STORE=sql");
        if (!billingEnabled) actions.add("Configure durable billing ledger and payment/invoice provider before enabling production monetization (APP_BILLING_ENABLED=true)");
        else if ("mock".equalsIgnoreCase(billingProvider)) actions.add("Replace mock billing provider with signed webhook, invoice and reconciliation adapters");
        if (readyForAutomatedLegalReasoning) actions.clear();
        String status = !infrastructureEnabled && !model.enabled()
                ? "OFFLINE_SAFE"
                : readyForAutomatedLegalReasoning ? "CONTROLLED_RELEASE_READY" : "VALIDATION_REQUIRED";
        return new ReadinessReport(
                Arrays.asList(environment.getActiveProfiles()),
                status,
                readyForAutomatedLegalReasoning,
                Map.copyOf(capabilities),
                actions
        );
    }

    private CapabilityStatus capability(boolean enabled, String detail) {
        return new CapabilityStatus(enabled ? "READY" : "DISABLED", detail);
    }

    private CapabilityStatus configuredCapability(boolean enabled, String detail) {
        return new CapabilityStatus(enabled ? "CONFIGURED_UNVERIFIED" : "DISABLED", detail);
    }

    public record ReadinessReport(List<String> activeProfiles,
                                  String status,
                                  boolean readyForAutomatedLegalReasoning,
                                  Map<String, CapabilityStatus> capabilities,
                                  List<String> requiredActions) {
    }

    public record CapabilityStatus(String status, String detail) {
    }
}
