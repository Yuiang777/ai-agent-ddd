package cn.apple.api;

import cn.apple.application.legal.matter.LegalMatterApplicationService;
import cn.apple.domain.legal.matter.DeadlineBasis;
import cn.apple.domain.legal.matter.EvidencePreservationStatus;
import cn.apple.domain.legal.matter.EvidenceSourceType;
import cn.apple.domain.legal.matter.MatterActorRole;
import cn.apple.domain.legal.matter.MatterStatus;
import cn.apple.domain.legal.matter.MatterScope;
import cn.apple.domain.legal.matter.MatterArtifact;
import cn.apple.domain.legal.matter.MatterTransitionCommand;
import cn.apple.domain.legal.matter.ReviewTaskDecision;
import cn.apple.domain.legal.matter.ReviewTaskType;
import cn.apple.config.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Matter command/query API. In offline mode the context falls back to a fixed
 * local tenant; once enterprise security is enabled both headers must be
 * injected by the authenticated gateway and are rejected when absent.
 */
@RestController
@RequestMapping("/api/legal/matters")
public class LegalMatterController {
    private final LegalMatterApplicationService service;
    private final boolean securityContextEnabled;

    public LegalMatterController(LegalMatterApplicationService service,
                                 @Value("${app.enterprise.security-context-enabled:false}") boolean securityContextEnabled) {
        this.service = service;
        this.securityContextEnabled = securityContextEnabled;
    }

    @GetMapping("/context")
    public ApiResponse<?> context() {
        return safe(() -> {
            MatterActorRole role = contextRole((MatterActorRole) null);
            contextTenantForMatterRead(null);
            if (role == MatterActorRole.CLIENT) return new ClientMatterContext(role, securityContextEnabled);
            return new MatterContext(contextTenantForMatterRead(null), contextActor(null), role, securityContextEnabled);
        });
    }

    public record MatterContext(String tenantId, String actorId, MatterActorRole role,
                                boolean securityContextEnabled) { }
    public record ClientMatterContext(MatterActorRole role, boolean securityContextEnabled) { }

    @GetMapping
    public ApiResponse<?> list(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        return safe(() -> {
            MatterActorRole role = contextRole((String) null);
            List<LegalMatterApplicationService.MatterView> matters = service.list(contextTenantForMatterRead(tenantId), contextActor(null), role);
            if (role != MatterActorRole.CLIENT) return matters;
            return matters.stream().map(this::clientView).toList();
        });
    }

    @GetMapping("/review-queue")
    public ApiResponse<List<cn.apple.domain.legal.matter.LegalReviewTask>> reviewQueue(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        return safe(() -> service.reviewQueue(contextTenant(tenantId), contextActor(null), contextRole((String) null)));
    }

    @PostMapping
    public ApiResponse<?> create(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorId,
            @RequestBody CreateMatterRequest request) {
        return safe(() -> {
            MatterActorRole role = contextRole((String) null);
            LegalMatterApplicationService.MatterView view = service.create(contextTenantForMatterRead(tenantId), contextActor(actorId), request.title(), request.clientReference(),
                    request.attributes() == null ? Map.of() : request.attributes(), request.scope(), role);
            return role == MatterActorRole.CLIENT ? clientView(view) : view;
        });
    }

    @GetMapping("/{matterId}")
    public ApiResponse<?> get(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @PathVariable String matterId) {
        return safe(() -> {
            MatterActorRole role = contextRole((String) null);
            LegalMatterApplicationService.MatterView view = service.get(contextTenantForMatterRead(tenantId), matterId, contextActor(null), role);
            return role == MatterActorRole.CLIENT ? clientView(view) : view;
        });
    }

    @GetMapping("/{matterId}/timeline")
    public ApiResponse<LegalMatterApplicationService.Timeline> timeline(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @PathVariable String matterId) {
        return safe(() -> {
            MatterActorRole role = contextRole((String) null);
            if (role == MatterActorRole.CLIENT) throw new SecurityException("INTERNAL_TIMELINE_FORBIDDEN");
            return service.timeline(contextTenantForMatterRead(tenantId), matterId, contextActor(null), role);
        });
    }

    private ClientMatterView clientView(LegalMatterApplicationService.MatterView view) {
        var matter = view.matter();
        List<ClientArtifact> releasedArtifacts = switch (matter.status()) {
            case SIGNED_OFF, CLOSED -> matter.artifacts().isEmpty() ? List.of()
                    : List.of(clientArtifact(matter.artifacts().get(matter.artifacts().size() - 1)));
            default -> List.of();
        };
        return new ClientMatterView(new ClientMatter(matter.id(), matter.title(), matter.clientReference(),
                matter.status(), matter.version(), matter.updatedAt(), releasedArtifacts), view.scope());
    }

    private ClientArtifact clientArtifact(MatterArtifact artifact) {
        return new ClientArtifact(artifact.version(), artifact.content(), artifact.sha256(), artifact.createdAt());
    }

    public record ClientMatterView(ClientMatter matter, MatterScope scope) { }
    public record ClientMatter(String id, String title, String clientReference, MatterStatus status,
                               long version, Instant updatedAt, List<ClientArtifact> artifacts) { }
    public record ClientArtifact(long version, String content, String sha256, Instant createdAt) { }
    @PostMapping("/{matterId}/transitions")
    public ApiResponse<LegalMatterApplicationService.MatterView> transition(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorId,
            @RequestHeader(value = "X-Actor-Role", required = false) String actorRole,
            @PathVariable String matterId,
            @RequestBody TransitionRequest request) {
        return safe(() -> service.transition(contextTenant(tenantId), matterId, request.target(),
                command(actorId, actorRole, request.reason(), request.reference(), request.expectedVersion())));
    }

    @PostMapping("/{matterId}/reviews")
    public ApiResponse<LegalMatterApplicationService.MatterView> submitReview(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorId,
            @RequestHeader(value = "X-Actor-Role", required = false) String actorRole,
            @PathVariable String matterId,
            @RequestBody SubmitReviewRequest request) {
        return safe(() -> service.submitForReview(contextTenant(tenantId), matterId, request.workflowRunId(), request.taskType(),
                command(actorId, actorRole, request.reason(), request.workflowRunId(), request.expectedVersion())));
    }

    @PostMapping("/{matterId}/reviews/{taskId}/decision")
    public ApiResponse<LegalMatterApplicationService.MatterView> decideReview(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @PathVariable String matterId,
            @PathVariable String taskId,
            @RequestBody ReviewDecisionRequest request) {
        return safe(() -> service.decideReview(contextTenant(tenantId), matterId, taskId,
                new ReviewTaskDecision(contextActor(request.reviewerId()), contextRole(request.reviewerRole()), request.approved(), request.opinion(),
                        request.reference(), Instant.now())));
    }

    @PostMapping("/{matterId}/artifacts")
    public ApiResponse<LegalMatterApplicationService.MatterView> publishArtifact(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @PathVariable String matterId, @RequestBody ArtifactRequest request) {
        return safe(() -> service.publishArtifact(contextTenant(tenantId), matterId, request.content(), request.workflowRunId(),
                new MatterTransitionCommand(contextActor(null), contextRole((String) null), "artifact publication", "",
                        request.expectedVersion(), Instant.now())));
    }

    @PostMapping("/{matterId}/evidence")
    public ApiResponse<LegalMatterApplicationService.MatterView> registerEvidence(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @PathVariable String matterId,
            @RequestBody EvidenceRequest request) {
        return safe(() -> service.addEvidence(contextTenant(tenantId), matterId,
                new LegalMatterApplicationService.EvidenceInput(request.id(), request.version(), request.objectReference(),
                        request.sha256(), request.sourceType(), request.acquiredAt(), contextActor(request.custodian()),
                        request.preservationStatus(), request.issueIds()), contextRole((String) null)));
    }

    @PostMapping("/{matterId}/evidence/{evidenceId}/preserve")
    public ApiResponse<LegalMatterApplicationService.MatterView> preserveEvidence(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorId,
            @PathVariable String matterId,
            @PathVariable String evidenceId) {
        return safe(() -> service.preserveEvidence(contextTenant(tenantId), matterId, evidenceId, contextActor(actorId), contextRole((String) null)));
    }

    @PostMapping("/{matterId}/deadlines")
    public ApiResponse<LegalMatterApplicationService.MatterView> proposeDeadline(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorId,
            @RequestHeader(value = "X-Actor-Role", required = false) String actorRole,
            @PathVariable String matterId,
            @RequestBody DeadlineRequest request) {
        return safe(() -> service.proposeDeadline(contextTenant(tenantId), matterId,
                new LegalMatterApplicationService.DeadlineInput(request.id(), request.dueAt(), request.zoneId(), request.basis(),
                        request.basisReference(), contextActor(actorId), contextRole(actorRole))));
    }

    @PostMapping("/{matterId}/deadlines/{deadlineId}/verify")
    public ApiResponse<LegalMatterApplicationService.MatterView> verifyDeadline(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorId,
            @RequestHeader(value = "X-Actor-Role", required = false) String actorRole,
            @PathVariable String matterId,
            @PathVariable String deadlineId) {
        return safe(() -> service.verifyDeadline(contextTenant(tenantId), matterId, deadlineId, contextActor(actorId),
                contextRole(actorRole), Instant.now()));
    }

    private String contextTenantForMatterRead(String ignoredClientValue) {
        TenantContext.Principal principal = TenantContext.current();
        if (securityContextEnabled) {
            if (principal == null) throw new SecurityException("AUTHENTICATED_TENANT_CONTEXT_REQUIRED");
            if (principal.role() == null) throw new SecurityException("AUTHENTICATED_ACTOR_ROLE_REQUIRED");
            if (principal.role() != MatterActorRole.LAWYER && principal.role() != MatterActorRole.PARALEGAL
                    && principal.role() != MatterActorRole.CLIENT) {
                throw new SecurityException("MATTER_READ_ROLE_REQUIRED");
            }
            return principal.tenantId();
        }
        return principal == null ? "offline-tenant" : principal.tenantId();
    }
    private String contextTenant(String ignoredClientValue) {
        TenantContext.Principal principal = TenantContext.current();
        if (securityContextEnabled) {
            if (principal == null) throw new SecurityException("AUTHENTICATED_TENANT_CONTEXT_REQUIRED");
            if (principal.role() == null) throw new SecurityException("AUTHENTICATED_ACTOR_ROLE_REQUIRED");
            if (principal.role() != MatterActorRole.LAWYER && principal.role() != MatterActorRole.PARALEGAL) {
                throw new SecurityException("INTERNAL_LEGAL_STAFF_REQUIRED");
            }
            return principal.tenantId();
        }
        return principal == null ? "offline-tenant" : principal.tenantId();
    }

    private String contextActor(String ignoredClientValue) {
        TenantContext.Principal principal = TenantContext.current();
        if (securityContextEnabled) {
            if (principal == null) throw new SecurityException("AUTHENTICATED_ACTOR_CONTEXT_REQUIRED");
            return principal.actorId();
        }
        return principal == null ? "offline-user" : principal.actorId();
    }

    private MatterTransitionCommand command(String actorId, String role, String reason, String reference, Long version) {
        return new MatterTransitionCommand(contextActor(actorId), contextRole(parseRole(role)), reason, reference, version, Instant.now());
    }

    private MatterActorRole contextRole(MatterActorRole clientRole) {
        TenantContext.Principal principal = TenantContext.current();
        if (securityContextEnabled) {
            if (principal == null || principal.role() == null) throw new SecurityException("AUTHENTICATED_ACTOR_ROLE_REQUIRED");
            return principal.role();
        }
        return principal == null || principal.role() == null ? (clientRole == null ? MatterActorRole.PARALEGAL : clientRole) : principal.role();
    }

    private MatterActorRole contextRole(String ignoredClientRole) {
        return contextRole((MatterActorRole) null);
    }

    private MatterActorRole parseRole(String value) {
        if (value == null || value.isBlank()) return securityContextEnabled ? null : MatterActorRole.PARALEGAL;
        try {
            return MatterActorRole.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid actor role");
        }
    }

    private <T> ApiResponse<T> safe(java.util.function.Supplier<T> operation) {
        try {
            return ApiResponse.ok(operation.get());
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    public record CreateMatterRequest(String title, String clientReference, Map<String, String> attributes,
                                      MatterScope scope) {
        public CreateMatterRequest(String title, String clientReference, Map<String, String> attributes) {
            this(title, clientReference, attributes, null);
        }
    }
    public record TransitionRequest(MatterStatus target, String reason, String reference, Long expectedVersion) { }
    public record SubmitReviewRequest(String workflowRunId, ReviewTaskType taskType, String reason, Long expectedVersion) { }
    public record ReviewDecisionRequest(String reviewerId, MatterActorRole reviewerRole, boolean approved,
                                        String opinion, String reference, Instant decidedAt) { }
    public record ArtifactRequest(String content, String workflowRunId, Long expectedVersion) { }
    public record EvidenceRequest(String id, int version, String objectReference, String sha256,
                                  EvidenceSourceType sourceType, Instant acquiredAt, String custodian,
                                  EvidencePreservationStatus preservationStatus, Set<String> issueIds) { }
    public record DeadlineRequest(String id, Instant dueAt, ZoneId zoneId, DeadlineBasis basis, String basisReference) { }
}
