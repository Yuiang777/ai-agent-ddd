package cn.apple.application.legal.matter;

import cn.apple.domain.legal.matter.DeadlineBasis;
import cn.apple.domain.legal.matter.EvidencePreservationStatus;
import cn.apple.domain.legal.matter.EvidenceSourceType;
import cn.apple.domain.legal.matter.LegalMatter;
import cn.apple.domain.legal.matter.MatterScope;
import cn.apple.domain.legal.matter.MatterArtifact;
import cn.apple.domain.legal.matter.ReviewArtifactBinding;
import cn.apple.domain.legal.matter.ReviewTaskStatus;
import cn.apple.domain.legal.matter.LegalReviewTask;
import cn.apple.domain.legal.matter.MatterActorRole;
import cn.apple.domain.legal.matter.MatterAuditEvent;
import cn.apple.domain.legal.matter.MatterDeadline;
import cn.apple.domain.legal.matter.MatterEvidence;
import cn.apple.domain.legal.matter.MatterStatus;
import cn.apple.domain.legal.matter.MatterTransition;
import cn.apple.domain.legal.matter.MatterTransitionCommand;
import cn.apple.domain.legal.matter.ReviewTaskDecision;
import cn.apple.domain.legal.matter.ReviewTaskType;
import cn.apple.domain.legal.matter.repository.LegalMatterRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Application boundary for the matter -> workflow -> review -> signoff loop.
 * It is intentionally storage-agnostic; the offline adapter is replaceable by
 * a transactional SQL/object-storage implementation without changing API
 * contracts or the domain state machine.
 */
@Service
public class LegalMatterApplicationService {
    private final LegalMatterRepository repository;

    public LegalMatterApplicationService(LegalMatterRepository repository) {
        this.repository = repository;
    }

    public MatterView create(String tenantId, String actorId, String title, String clientReference) {
        return repository.inTransaction(() -> {
            return create(tenantId, actorId, title, clientReference, Map.of());
        });
    }

    public MatterView create(String tenantId, String actorId, String title, String clientReference,
                             Map<String, String> attributes) {
        return repository.inTransaction(() -> {
            return create(tenantId, actorId, title, clientReference, attributes, MatterScope.unspecified());
        });
    }

    public MatterView create(String tenantId, String actorId, String title, String clientReference,
                             Map<String, String> attributes, MatterScope scope) {
        return create(tenantId, actorId, title, clientReference, attributes, scope, MatterActorRole.CLIENT);
    }

    public MatterView create(String tenantId, String actorId, String title, String clientReference,
                             Map<String, String> attributes, MatterScope scope, MatterActorRole actorRole) {
        java.util.Objects.requireNonNull(actorRole, "actorRole");
        return repository.inTransaction(() -> {
            String tenant = required(tenantId, "tenantId");
            java.util.LinkedHashMap<String, String> securedAttributes = new java.util.LinkedHashMap<>();
            if (attributes != null && attributes.keySet().stream().anyMatch(key ->
                    key == null || key.trim().startsWith(MatterScope.PREFIX))) {
                throw new IllegalArgumentException("RESERVED_MATTER_SCOPE_ATTRIBUTE");
            }
            if (attributes != null) attributes.forEach((key, value) -> {
                if (!"ownerActorId".equals(key.trim())) securedAttributes.put(key, value);
            });
            securedAttributes.putAll((scope == null ? MatterScope.unspecified() : scope).toAttributes());
            securedAttributes.put("ownerActorId", required(actorId, "actorId"));
            LegalMatter matter = LegalMatter.open(tenant, title, clientReference, securedAttributes, Instant.now());
            repository.create(matter);
            audit(tenant, matter.id(), actorId, actorRole, "MATTER_CREATED", "", "matter opened",
                    Map.of("status", matter.status().name()));
            return view(matter);
        });
    }

    public List<MatterView> list(String tenantId) {
        return repository.list(required(tenantId, "tenantId")).stream().map(this::view).toList();
    }

    public List<LegalReviewTask> reviewQueue(String tenantId) {
        String tenant = required(tenantId, "tenantId");
        Map<String, java.util.Optional<LegalMatter>> matters = new java.util.HashMap<>();
        Map<String, ReviewArtifactBinding> bindings = new java.util.HashMap<>();
        return repository.reviewTasks(tenant).stream().filter(task -> {
            if (task.status() != ReviewTaskStatus.PENDING && task.status() != ReviewTaskStatus.IN_REVIEW) return false;
            if (task.artifactBinding() == null) return false;
            LegalMatter matter = matters.computeIfAbsent(task.matterId(), id -> repository.find(tenant, id)).orElse(null);
            if (matter == null || matter.artifacts().isEmpty()) return false;
            MatterStatus stage = task.type() == ReviewTaskType.LAWYER_SIGNOFF
                    ? MatterStatus.SIGNOFF_REQUIRED : MatterStatus.REVIEW_REQUIRED;
            return matter.status() == stage && task.artifactBinding().equals(
                    bindings.computeIfAbsent(matter.id(), ignored -> currentBinding(matter)));
        }).toList();
    }

    public MatterView get(String tenantId, String matterId) {
        return view(find(tenantId, matterId));
    }

    public List<MatterView> list(String tenantId, String actorId, MatterActorRole role) {
        String actor = required(actorId, "actorId");
        java.util.Objects.requireNonNull(role, "actorRole");
        return list(tenantId).stream().filter(item -> role != MatterActorRole.CLIENT
                || actor.equals(item.matter().attributes().get("ownerActorId"))).toList();
    }

    public MatterView get(String tenantId, String matterId, String actorId, MatterActorRole role) {
        LegalMatter matter = find(tenantId, matterId);
        authorize(matter, actorId, role, false);
        return view(matter);
    }

    public Timeline timeline(String tenantId, String matterId, String actorId, MatterActorRole role) {
        authorize(find(tenantId, matterId), actorId, role, false);
        return timeline(tenantId, matterId);
    }

    public List<LegalReviewTask> reviewQueue(String tenantId, String actorId, MatterActorRole role) {
        required(actorId, "actorId");
        requireInternalStaff(role);
        return reviewQueue(tenantId);
    }

    public MatterView transition(String tenantId, String matterId, MatterStatus target,
                                 MatterTransitionCommand command) {
        if (target == MatterStatus.SIGNED_OFF || target == MatterStatus.SIGNOFF_REQUIRED) {
            throw new SecurityException("REVIEW_DECISION_REQUIRED");
        }
        return transitionReviewed(tenantId, matterId, target, command);
    }

    private MatterView transitionReviewed(String tenantId, String matterId, MatterStatus target,
                                          MatterTransitionCommand command) {
        return repository.inTransaction(() -> {
            LegalMatter matter = find(tenantId, matterId);
            authorize(matter, command.actorId(), command.actorRole(), true);
            synchronized (matter) {
                long expected = command.expectedVersion() == null ? matter.version() : command.expectedVersion();
                MatterStatus before = matter.status();
                MatterTransition transition = matter.transitionTo(target,
                        new MatterTransitionCommand(command.actorId(), command.actorRole(), command.reason(),
                                command.reference(), expected, command.occurredAt()));
                repository.save(matter, expected);
                audit(matter.tenantId(), matter.id(), command.actorId(), command.actorRole(),
                        "MATTER_STATUS_CHANGED", command.reference(), transition.reason(),
                        Map.of("from", before.name(), "to", target.name(), "version", matter.version()));
                return view(matter);
            }
        });
    }

    public MatterView startConflictCheck(String tenantId, String matterId, MatterTransitionCommand command) {
        return repository.inTransaction(() -> {
            return transition(tenantId, matterId, MatterStatus.CONFLICT_CHECK, command);
        });
    }

    public MatterView passConflictCheck(String tenantId, String matterId, String reference,
                                        MatterTransitionCommand command) {
        return repository.inTransaction(() -> {
            MatterTransitionCommand checked = command(command, reference, "conflict check passed");
            return transition(tenantId, matterId, MatterStatus.ENGAGEMENT_PENDING, checked);
        });
    }

    public MatterView authorizeEngagement(String tenantId, String matterId, String reference,
                                          MatterTransitionCommand command) {
        return repository.inTransaction(() -> {
            MatterTransitionCommand checked = command(command, reference, "engagement authority confirmed");
            return transition(tenantId, matterId, MatterStatus.MATERIAL_PENDING, checked);
        });
    }

    public MatterView submitForReview(String tenantId, String matterId, String workflowRunId,
                                      ReviewTaskType taskType, MatterTransitionCommand command) {
        return repository.inTransaction(() -> {
            MatterView current = get(tenantId, matterId);
            authorize(current.matter(), command.actorId(), command.actorRole(), true);
            if (command.actorRole() == MatterActorRole.CLIENT) throw new SecurityException("REVIEW_SUBMISSION_FORBIDDEN");
            ReviewArtifactBinding binding = currentBinding(current.matter());
            if (taskType == ReviewTaskType.LAWYER_SIGNOFF) {
                requireQualityReview(tenantId, matterId, binding);
                if (current.matter().status() != MatterStatus.SIGNOFF_REQUIRED) throw new IllegalStateException("SIGNOFF_STAGE_REQUIRED");
            } else if (current.matter().status() != MatterStatus.ANALYSIS) {
                throw new IllegalStateException("ANALYSIS_STAGE_REQUIRED");
            }
            if (repository.reviewTasks(tenantId, matterId).stream().anyMatch(task ->
                    (task.status() == ReviewTaskStatus.PENDING || task.status() == ReviewTaskStatus.IN_REVIEW)
                            && task.type() == (taskType == null ? ReviewTaskType.QUALITY_REVIEW : taskType)
                            && binding.equals(task.artifactBinding()))) {
                throw new IllegalStateException("REVIEW_TASK_ALREADY_PENDING");
            }
            if (command.expectedVersion() == null || command.expectedVersion() != current.matter().version()) {
                throw new IllegalStateException("MATTER_VERSION_CONFLICT");
            }
            if (taskType != ReviewTaskType.LAWYER_SIGNOFF) {
                transitionReviewed(tenantId, matterId, MatterStatus.REVIEW_REQUIRED,
                        command(command, workflowRunId, "workflow output submitted for independent review"));
            }
            LegalReviewTask task = LegalReviewTask.create(
                    "REV-" + UUID.randomUUID().toString().replace("-", ""),
                    current.matter().tenantId(), current.matter().id(),
                    taskType == null ? ReviewTaskType.QUALITY_REVIEW : taskType, Instant.now());
            task.bindArtifact(binding);
            repository.saveReviewTask(task);
            audit(current.matter().tenantId(), current.matter().id(), command.actorId(), command.actorRole(),
                    "REVIEW_TASK_CREATED", task.id(), "review task created",
                    Map.of("workflowRunId", workflowRunId == null ? "" : workflowRunId, "taskType", task.type().name()));
            return get(tenantId, matterId);
        });
    }

    /** Creates a task without changing matter status; useful for preflight assignment. */
    public LegalReviewTask createReviewTask(String tenantId, String matterId, ReviewTaskType taskType,
                                             String actorId, MatterActorRole actorRole, Instant createdAt) {
        requireInternalStaff(actorRole);
        return repository.inTransaction(() -> {
            LegalMatter matter = find(tenantId, matterId);
            authorize(matter, actorId, actorRole, false);
            LegalReviewTask task = LegalReviewTask.create("REV-" + UUID.randomUUID().toString().replace("-", ""),
                    matter.tenantId(), matter.id(), taskType == null ? ReviewTaskType.QUALITY_REVIEW : taskType, createdAt);
            repository.saveReviewTask(task);
            audit(tenantId, matterId, actorId, actorRole, "REVIEW_TASK_CREATED", task.id(),
                    "review task assigned", Map.of("taskType", task.type().name()));
            return task;
        });
    }

    public LegalReviewTask startReview(String tenantId, String matterId, String taskId, String reviewerId,
                                        MatterActorRole reviewerRole) {
        requireInternalStaff(reviewerRole);
        return repository.inTransaction(() -> {
            LegalMatter matter = find(tenantId, matterId);
            authorize(matter, reviewerId, reviewerRole, false);
            LegalReviewTask task = repository.findReviewTask(tenantId, taskId)
                    .orElseThrow(() -> new IllegalArgumentException("REVIEW_TASK_NOT_FOUND"));
            if (!task.matterId().equals(matterId)) throw new SecurityException("REVIEW_TASK_MATTER_MISMATCH");
            MatterStatus requiredStage = task.type() == ReviewTaskType.LAWYER_SIGNOFF
                    ? MatterStatus.SIGNOFF_REQUIRED : MatterStatus.REVIEW_REQUIRED;
            if (matter.status() != requiredStage) throw new IllegalStateException("REVIEW_STAGE_REQUIRED");
            requireIndependentReviewer(matter, task, reviewerId);
            if (task.type() == ReviewTaskType.LAWYER_SIGNOFF) requireQualityReview(tenantId, matterId, task.artifactBinding());
            long expected = task.version();
            task.start(reviewerId, reviewerRole);
            repository.saveReviewTask(task, expected);
            audit(tenantId, matterId, reviewerId, reviewerRole, "REVIEW_STARTED", taskId, "human review started", Map.of());
            return task;
        });
    }

    public MatterView decideReview(String tenantId, String matterId, String taskId, ReviewTaskDecision decision) {
        requireInternalStaff(decision.reviewerRole());
        return repository.inTransaction(() -> {
            LegalMatter matter = find(tenantId, matterId);
            authorize(matter, decision.reviewerId(), decision.reviewerRole(), false);
            LegalReviewTask task = repository.findReviewTask(tenantId, taskId)
                    .orElseThrow(() -> new IllegalArgumentException("REVIEW_TASK_NOT_FOUND"));
            if (!task.matterId().equals(matter.id())) throw new SecurityException("REVIEW_TASK_MATTER_MISMATCH");
            synchronized (matter) {
                if (task.artifactBinding() == null) throw new IllegalStateException("REVIEW_ARTIFACT_BINDING_REQUIRED");
                if (!task.artifactBinding().equals(currentBinding(matter))) throw new IllegalStateException("REVIEW_ARTIFACT_STALE");
                MatterStatus requiredStage = task.type() == ReviewTaskType.LAWYER_SIGNOFF
                        ? MatterStatus.SIGNOFF_REQUIRED : MatterStatus.REVIEW_REQUIRED;
                if (matter.status() != requiredStage) throw new IllegalStateException("REVIEW_STAGE_REQUIRED");
                if (task.type() == ReviewTaskType.LAWYER_SIGNOFF) requireQualityReview(tenantId, matterId, task.artifactBinding());
                requireIndependentReviewer(matter, task, decision.reviewerId());
                long expectedTaskVersion = task.version();
                if (task.status() == cn.apple.domain.legal.matter.ReviewTaskStatus.PENDING) {
                    task.start(decision.reviewerId(), decision.reviewerRole());
                }
                task.decide(decision);
                repository.saveReviewTask(task, expectedTaskVersion);
                if (!decision.approved()) {
                    if (matter.status() == MatterStatus.SIGNOFF_REQUIRED) {
                        transitionReviewed(tenantId, matterId, MatterStatus.REVIEW_REQUIRED,
                                new MatterTransitionCommand(decision.reviewerId(), decision.reviewerRole(),
                                        "signoff returned", decision.reference(), matter.version(), decision.decidedAt()));
                        matter = find(tenantId, matterId);
                    }
                    transitionReviewed(tenantId, matterId, MatterStatus.MATERIAL_PENDING,
                            new MatterTransitionCommand(decision.reviewerId(), decision.reviewerRole(),
                                    "review returned: " + decision.opinion(), decision.reference(), matter.version(), decision.decidedAt()));
                } else if (task.type() == ReviewTaskType.LAWYER_SIGNOFF) {
                    transitionReviewed(tenantId, matterId, MatterStatus.SIGNED_OFF,
                            new MatterTransitionCommand(decision.reviewerId(), decision.reviewerRole(), decision.opinion(),
                                    decision.reference(), matter.version(), decision.decidedAt()));
                } else {
                    transitionReviewed(tenantId, matterId, MatterStatus.SIGNOFF_REQUIRED,
                            new MatterTransitionCommand(decision.reviewerId(), decision.reviewerRole(),
                                    "quality review passed; lawyer signoff required", decision.reference(), matter.version(), decision.decidedAt()));
                }
                audit(tenantId, matterId, decision.reviewerId(), decision.reviewerRole(), "REVIEW_DECIDED",
                        task.id(), decision.opinion(), Map.of("approved", decision.approved(), "taskStatus", task.status().name()));
                return get(tenantId, matterId);
            }
        });
    }

    public MatterView addEvidence(String tenantId, String matterId, EvidenceInput input) {
        return addEvidence(tenantId, matterId, input, MatterActorRole.PARALEGAL);
    }

    public MatterView addEvidence(String tenantId, String matterId, EvidenceInput input, MatterActorRole actorRole) {
        requireInternalStaff(actorRole);
        return repository.inTransaction(() -> {
            LegalMatter matter = find(tenantId, matterId);
            authorize(matter, input.custodian(), actorRole, false);
            if (matter.status() == MatterStatus.SIGNED_OFF || matter.status() == MatterStatus.CLOSED) {
                throw new IllegalStateException("SIGNED_MATTER_EVIDENCE_FROZEN");
            }
            MatterEvidence item = new MatterEvidence(
                    input.id() == null || input.id().isBlank() ? "EVD-" + UUID.randomUUID().toString().replace("-", "") : input.id(),
                    tenantId, matterId, input.version() <= 0 ? 1 : input.version(), input.objectReference(), input.sha256(),
                    input.sourceType(), input.acquiredAt(), input.custodian(), input.preservationStatus(), input.issueIds());
            MatterEvidence existing = repository.evidence(tenantId, matterId).stream()
                    .filter(value -> value.id().equals(item.id()) && value.version() == item.version())
                    .findFirst().orElse(null);
            if (item.equals(existing)) return get(tenantId, matterId);
            repository.saveEvidence(item);
            resetAnalysis(tenantId, matterId, input.custodian(), actorRole);
            audit(tenantId, matterId, input.custodian(), actorRole, "EVIDENCE_REGISTERED", item.id(),
                    "evidence metadata registered", Map.of("sha256", item.sha256(), "version", item.version(), "sourceType", item.sourceType().name()));
            return get(tenantId, matterId);
        });
    }

    public MatterView preserveEvidence(String tenantId, String matterId, String evidenceId, String actorId) {
        return preserveEvidence(tenantId, matterId, evidenceId, actorId, MatterActorRole.PARALEGAL);
    }

    public MatterView preserveEvidence(String tenantId, String matterId, String evidenceId, String actorId, MatterActorRole actorRole) {
        requireInternalStaff(actorRole);
        return repository.inTransaction(() -> {
            LegalMatter matter = find(tenantId, matterId);
            authorize(matter, actorId, actorRole, false);
            if (matter.status() == MatterStatus.SIGNED_OFF || matter.status() == MatterStatus.CLOSED) {
                throw new IllegalStateException("SIGNED_MATTER_EVIDENCE_FROZEN");
            }
            MatterEvidence current = repository.evidence(tenantId, matterId).stream()
                    .filter(item -> item.id().equals(evidenceId))
                    .max(java.util.Comparator.comparingInt(MatterEvidence::version))
                    .orElseThrow(() -> new IllegalArgumentException("EVIDENCE_NOT_FOUND"));
            if (current.preservationStatus() == EvidencePreservationStatus.PRESERVED) return get(tenantId, matterId);
            repository.saveEvidence(current.markPreserved(actorId));
            resetAnalysis(tenantId, matterId, actorId, actorRole);
            audit(tenantId, matterId, actorId, actorRole, "EVIDENCE_PRESERVED", evidenceId,
                    "evidence preservation status changed", Map.of("status", EvidencePreservationStatus.PRESERVED.name()));
            return get(tenantId, matterId);
        });
    }

    public MatterView proposeDeadline(String tenantId, String matterId, DeadlineInput input) {
        requireInternalStaff(input.actorRole());
        return repository.inTransaction(() -> {
            LegalMatter matter = find(tenantId, matterId);
            authorize(matter, input.actorId(), input.actorRole(), false);
            requireDeadlineWritable(matter);
            MatterDeadline deadline = new MatterDeadline(
                    input.id() == null || input.id().isBlank() ? "DDL-" + UUID.randomUUID().toString().replace("-", "") : input.id(),
                    tenantId, matterId, java.util.Objects.requireNonNull(input.dueAt(), "DEADLINE_DUE_AT_REQUIRED"),
                    java.util.Objects.requireNonNull(input.zoneId(), "DEADLINE_ZONE_REQUIRED"), input.basis(), input.basisReference(), null, "", null);
            MatterDeadline existing = repository.deadlines(tenantId, matterId).stream()
                    .filter(value -> value.id().equals(deadline.id())).findFirst().orElse(null);
            if (deadline.equals(existing)) return view(matter);
            if (existing != null) throw new IllegalStateException("DEADLINE_UPDATE_CONFLICT");
            repository.saveDeadline(deadline);
            audit(tenantId, matterId, input.actorId(), input.actorRole(), "DEADLINE_PROPOSED", deadline.id(),
                    "deadline requires human verification before reminders", Map.of("dueAt", deadline.dueAt().toString(), "basis", deadline.basis().name()));
            return view(matter);
        });
    }

    public MatterView verifyDeadline(String tenantId, String matterId, String deadlineId, String reviewerId) {
        return repository.inTransaction(() -> {
            return verifyDeadline(tenantId, matterId, deadlineId, reviewerId, MatterActorRole.LAWYER, Instant.now());
        });
    }

    public MatterView verifyDeadline(String tenantId, String matterId, String deadlineId, String reviewerId, Instant verifiedAt) {
        return repository.inTransaction(() -> {
            return verifyDeadline(tenantId, matterId, deadlineId, reviewerId, MatterActorRole.LAWYER, verifiedAt);
        });
    }

    public MatterView verifyDeadline(String tenantId, String matterId, String deadlineId, String reviewerId,
                                     MatterActorRole reviewerRole, Instant verifiedAt) {
        requireInternalStaff(reviewerRole);
        return repository.inTransaction(() -> {
            if (reviewerRole != MatterActorRole.LAWYER) throw new SecurityException("LAWYER_DEADLINE_VERIFICATION_REQUIRED");
            LegalMatter matter = find(tenantId, matterId);
            authorize(matter, reviewerId, reviewerRole, false);
            requireDeadlineWritable(matter);
            MatterDeadline current = repository.deadlines(tenantId, matterId).stream()
                    .filter(item -> item.id().equals(deadlineId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("DEADLINE_NOT_FOUND"));
            MatterDeadline verified = current.verify(reviewerId, verifiedAt);
            repository.saveDeadline(verified);
            audit(tenantId, matterId, reviewerId, reviewerRole, "DEADLINE_VERIFIED", deadlineId,
                    "deadline source and calculation verified by lawyer", Map.of("status", verified.status().name()));
            return view(matter);
        });
    }

    public Timeline timeline(String tenantId, String matterId) {
        LegalMatter matter = find(tenantId, matterId);
        return new Timeline(matter, repository.audit(tenantId, matterId), repository.reviewTasks(tenantId, matterId),
                repository.evidence(tenantId, matterId), repository.deadlines(tenantId, matterId));
    }

    public MatterView publishArtifact(String tenantId, String matterId, String content, String workflowRunId,
                                      MatterTransitionCommand command) {
        return repository.inTransaction(() -> {
            LegalMatter matter = find(tenantId, matterId);
            authorize(matter, command.actorId(), command.actorRole(), true);
            if (command.actorRole() == MatterActorRole.CLIENT) throw new SecurityException("ARTIFACT_PUBLICATION_FORBIDDEN");
            if (command.expectedVersion() == null) throw new IllegalArgumentException("EXPECTED_VERSION_REQUIRED");
            long expected = command.expectedVersion();
            MatterArtifact artifact = matter.publishArtifact(content, workflowRunId, command.actorId(), expected, Instant.now());
            repository.save(matter, expected);
            audit(tenantId, matterId, command.actorId(), command.actorRole(), "ARTIFACT_PUBLISHED", artifact.sha256(),
                    "new version requires review", Map.of("artifactVersion", artifact.version()));
            resetAnalysis(tenantId, matterId, command.actorId(), command.actorRole());
            return get(tenantId, matterId);
        });
    }

    private void resetAnalysis(String tenantId, String matterId, String actorId, MatterActorRole role) {
        LegalMatter matter = find(tenantId, matterId);
        if (matter.status() == MatterStatus.SIGNOFF_REQUIRED) {
            transitionReviewed(tenantId, matterId, MatterStatus.REVIEW_REQUIRED,
                    new MatterTransitionCommand(actorId, role, "inputs changed", "", matter.version(), Instant.now()));
            matter = find(tenantId, matterId);
        }
        if (matter.status() == MatterStatus.REVIEW_REQUIRED) {
            transitionReviewed(tenantId, matterId, MatterStatus.ANALYSIS,
                    new MatterTransitionCommand(actorId, role, "inputs changed", "", matter.version(), Instant.now()));
        }
    }

    private ReviewArtifactBinding currentBinding(LegalMatter matter) {
        if (matter.artifacts().isEmpty()) throw new IllegalStateException("ARTIFACT_REQUIRED");
        MatterArtifact artifact = matter.artifacts().get(matter.artifacts().size() - 1);
        String evidenceState = repository.evidence(matter.tenantId(), matter.id()).stream()
                .sorted(java.util.Comparator.comparing(MatterEvidence::id).thenComparingInt(MatterEvidence::version))
                .map(item -> item.id().length() + ":" + item.id() + ":" + item.version() + ":" + item.sha256())
                .collect(java.util.stream.Collectors.joining("\n"));
        return new ReviewArtifactBinding(artifact.version(), artifact.sha256(), MatterArtifact.digest(evidenceState));
    }

    private void requireIndependentReviewer(LegalMatter matter, LegalReviewTask task, String reviewerId) {
        String actor = required(reviewerId, "reviewerId");
        if (task.artifactBinding() == null) throw new IllegalStateException("REVIEW_ARTIFACT_BINDING_REQUIRED");
        if (!task.artifactBinding().equals(currentBinding(matter))) throw new IllegalStateException("REVIEW_ARTIFACT_STALE");
        MatterArtifact artifact = matter.artifacts().get(matter.artifacts().size() - 1);
        if (actor.equals(artifact.createdBy().trim())) throw new SecurityException("ARTIFACT_AUTHOR_CANNOT_REVIEW");
        if (task.type() == ReviewTaskType.LAWYER_SIGNOFF && repository.reviewTasks(matter.tenantId(), matter.id()).stream()
                .anyMatch(review -> review.type() == ReviewTaskType.QUALITY_REVIEW
                        && review.status() == ReviewTaskStatus.COMPLETED
                        && task.artifactBinding().equals(review.artifactBinding())
                        && actor.equals(review.reviewerId().trim()))) {
            throw new SecurityException("QUALITY_REVIEWER_CANNOT_SIGNOFF");
        }
        if (task.status() == ReviewTaskStatus.IN_REVIEW && !actor.equals(task.reviewerId().trim())) {
            throw new SecurityException("REVIEW_ASSIGNEE_MISMATCH");
        }
    }

    private void requireQualityReview(String tenantId, String matterId, ReviewArtifactBinding binding) {
        boolean reviewed = repository.reviewTasks(tenantId, matterId).stream().anyMatch(task ->
                task.type() == ReviewTaskType.QUALITY_REVIEW && task.status() == ReviewTaskStatus.COMPLETED
                        && binding.equals(task.artifactBinding()));
        if (!reviewed) throw new IllegalStateException("CURRENT_ARTIFACT_QUALITY_REVIEW_REQUIRED");
    }

    private MatterView view(LegalMatter matter) {
        return new MatterView(matter, repository.reviewTasks(matter.tenantId(), matter.id()),
                repository.evidence(matter.tenantId(), matter.id()), repository.deadlines(matter.tenantId(), matter.id()),
                MatterScope.fromAttributes(matter.attributes()), matter.artifacts().isEmpty() ? null : currentBinding(matter));
    }

    private LegalMatter find(String tenantId, String matterId) {
        return repository.find(required(tenantId, "tenantId"), required(matterId, "matterId"))
                .orElseThrow(() -> new IllegalArgumentException("MATTER_NOT_FOUND"));
    }

    private void requireDeadlineWritable(LegalMatter matter) {
        if (matter.status() == MatterStatus.SIGNED_OFF || matter.status() == MatterStatus.CLOSED || matter.status() == MatterStatus.REJECTED) {
            throw new IllegalStateException("SIGNED_MATTER_DEADLINES_FROZEN");
        }
    }

    private void requireInternalStaff(MatterActorRole role) {
        if (role != MatterActorRole.LAWYER && role != MatterActorRole.PARALEGAL) {
            throw new SecurityException("INTERNAL_LEGAL_STAFF_REQUIRED");
        }
    }

    private void authorize(LegalMatter matter, String actorId, MatterActorRole role, boolean write) {
        String actor = required(actorId, "actorId");
        java.util.Objects.requireNonNull(role, "actorRole");
        if (role == MatterActorRole.SYSTEM) return;
        if (write && role == MatterActorRole.CLIENT) throw new SecurityException("MATTER_WRITE_FORBIDDEN");
        String owner = matter.attributes().get("ownerActorId");
        if (role == MatterActorRole.CLIENT && !actor.equals(owner)) {
            throw new SecurityException("MATTER_ACCESS_DENIED");
        }
    }

    private void audit(String tenantId, String matterId, String actorId, MatterActorRole role,
                       String type, String reference, String reason, Map<String, Object> data) {
        repository.appendAudit(new MatterAuditEvent("AUD-" + UUID.randomUUID().toString().replace("-", ""),
                tenantId, matterId, type, required(actorId, "actorId"), role, reference, reason, data, Instant.now()));
    }

    private MatterTransitionCommand command(MatterTransitionCommand source, String reference, String reason) {
        return new MatterTransitionCommand(source.actorId(), source.actorRole(), reason,
                reference == null ? source.reference() : reference, source.expectedVersion(), source.occurredAt());
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    public record MatterView(LegalMatter matter, List<LegalReviewTask> reviewTasks,
                             List<MatterEvidence> evidence, List<MatterDeadline> deadlines, MatterScope scope,
                             ReviewArtifactBinding currentArtifactBinding) { }

    public record Timeline(LegalMatter matter, List<MatterAuditEvent> audit,
                           List<LegalReviewTask> reviewTasks, List<MatterEvidence> evidence,
                           List<MatterDeadline> deadlines) { }

    public record EvidenceInput(String id, int version, String objectReference, String sha256,
                                EvidenceSourceType sourceType, Instant acquiredAt, String custodian,
                                EvidencePreservationStatus preservationStatus, java.util.Set<String> issueIds) { }

    public record DeadlineInput(String id, Instant dueAt, ZoneId zoneId, DeadlineBasis basis,
                                String basisReference, String actorId, MatterActorRole actorRole) { }
}
