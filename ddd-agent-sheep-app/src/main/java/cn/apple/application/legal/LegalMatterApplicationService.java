package cn.apple.application.legal;

import cn.apple.domain.legal.matter.LegalMatter;
import cn.apple.domain.legal.matter.LegalReviewTask;
import cn.apple.domain.legal.matter.MatterActorRole;
import cn.apple.domain.legal.matter.MatterDeadline;
import cn.apple.domain.legal.matter.MatterEvidence;
import cn.apple.domain.legal.matter.MatterStatus;
import cn.apple.domain.legal.matter.MatterTransition;
import cn.apple.domain.legal.matter.MatterTransitionCommand;
import cn.apple.domain.legal.matter.ReviewTaskDecision;
import cn.apple.domain.legal.matter.ReviewTaskType;
import cn.apple.domain.legal.matter.repository.LegalMatterRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Compatibility facade for callers that still use the original application
 * package. The authoritative implementation lives in the matter subpackage;
 * this facade is intentionally not a Spring bean so there is only one runtime
 * store and one audit boundary.
 */
@Deprecated
public class LegalMatterApplicationService {
    private final cn.apple.application.legal.matter.LegalMatterApplicationService delegate;

    public LegalMatterApplicationService() {
        this(new cn.apple.application.legal.matter.InMemoryLegalMatterRepository());
    }

    public LegalMatterApplicationService(LegalMatterRepository repository) {
        this.delegate = new cn.apple.application.legal.matter.LegalMatterApplicationService(repository);
    }

    public LegalMatter createMatter(String tenantId, String title, String clientReference, Instant now) {
        return delegate.create(tenantId, "offline-user", title, clientReference).matter();
    }

    public Optional<LegalMatter> findMatter(String tenantId, String matterId) {
        try {
            return Optional.of(delegate.get(tenantId, matterId).matter());
        } catch (IllegalArgumentException e) {
            if ("MATTER_NOT_FOUND".equals(e.getMessage())) return Optional.empty();
            throw e;
        }
    }

    public MatterTransition transitionMatter(String tenantId, String matterId, MatterStatus target,
                                             MatterTransitionCommand command) {
        LegalMatter matter = delegate.transition(tenantId, matterId, target, command).matter();
        List<MatterTransition> history = matter.transitionHistory();
        return history.get(history.size() - 1);
    }

    public LegalReviewTask createReviewTask(String tenantId, String matterId, ReviewTaskType type, Instant now) {
        return delegate.createReviewTask(tenantId, matterId, type, "offline-user", MatterActorRole.PARALEGAL, now);
    }

    public LegalReviewTask startReview(String tenantId, String matterId, String reviewId,
                                       String reviewerId, MatterActorRole reviewerRole) {
        return delegate.startReview(tenantId, matterId, reviewId, reviewerId, reviewerRole);
    }

    public LegalReviewTask decideReview(String tenantId, String matterId, String reviewId,
                                        ReviewTaskDecision decision) {
        delegate.decideReview(tenantId, matterId, reviewId, decision);
        return delegate.timeline(tenantId, matterId).reviewTasks().stream()
                .filter(value -> value.id().equals(reviewId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("REVIEW_TASK_NOT_FOUND"));
    }

    public MatterEvidence registerEvidence(String tenantId, MatterEvidence item) {
        return delegate.addEvidence(tenantId, item.matterId(),
                new cn.apple.application.legal.matter.LegalMatterApplicationService.EvidenceInput(
                        item.id(), item.version(), item.objectReference(), item.sha256(), item.sourceType(), item.acquiredAt(),
                        item.custodian(), item.preservationStatus(), item.issueIds())).evidence().stream()
                .filter(value -> value.id().equals(item.id())).findFirst().orElse(item);
    }

    public List<MatterEvidence> listEvidence(String tenantId, String matterId) {
        return delegate.get(tenantId, matterId).evidence();
    }

    public MatterDeadline registerDeadline(String tenantId, MatterDeadline deadline) {
        return delegate.proposeDeadline(tenantId, deadline.matterId(),
                new cn.apple.application.legal.matter.LegalMatterApplicationService.DeadlineInput(
                        deadline.id(), deadline.dueAt(), deadline.zoneId(), deadline.basis(), deadline.basisReference(),
                        "offline-user", MatterActorRole.PARALEGAL)).deadlines().stream()
                .filter(value -> value.id().equals(deadline.id())).findFirst().orElse(deadline);
    }

    public MatterDeadline verifyDeadline(String tenantId, String matterId, String deadlineId,
                                         String reviewerId, Instant at) {
        return delegate.verifyDeadline(tenantId, matterId, deadlineId, reviewerId, at).deadlines().stream()
                .filter(value -> value.id().equals(deadlineId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("DEADLINE_NOT_FOUND"));
    }
}
