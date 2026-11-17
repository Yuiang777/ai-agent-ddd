package cn.apple.domain.legal.matter;

import java.time.Instant;

/** Review/signoff task with explicit reviewer and opinion requirements. */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
public final class LegalReviewTask {
    private final String id;
    private final String tenantId;
    private final String matterId;
    private final ReviewTaskType type;
    private ReviewTaskStatus status;
    private long version;
    private String reviewerId = "";
    private String opinion = "";
    private String decisionReference = "";
    private Instant decidedAt;
    private ReviewArtifactBinding artifactBinding;

    private LegalReviewTask(String id, String tenantId, String matterId, ReviewTaskType type, Instant createdAt) {
        this.id = required(id, "id");
        this.tenantId = required(tenantId, "tenantId");
        this.matterId = required(matterId, "matterId");
        this.type = type == null ? ReviewTaskType.QUALITY_REVIEW : type;
        this.status = ReviewTaskStatus.PENDING;
        this.version = 0;
        this.decidedAt = createdAt;
    }

    public static LegalReviewTask create(String id, String tenantId, String matterId,
                                         ReviewTaskType type, Instant createdAt) {
        return new LegalReviewTask(id, tenantId, matterId, type, createdAt == null ? Instant.now() : createdAt);
    }

    public static LegalReviewTask rehydrate(String id, String tenantId, String matterId, ReviewTaskType type,
                                           ReviewTaskStatus status, long version, String reviewerId,
                                           String opinion, String decisionReference, Instant decidedAt) {
        if (status == null || version < 0) throw new IllegalArgumentException("INVALID_REVIEW_SNAPSHOT");
        LegalReviewTask task = new LegalReviewTask(id, tenantId, matterId, type, decidedAt);
        task.status = status;
        task.version = version;
        task.reviewerId = reviewerId == null ? "" : reviewerId;
        task.opinion = opinion == null ? "" : opinion;
        task.decisionReference = decisionReference == null ? "" : decisionReference;
        return task;
    }

    public void start(String reviewerId, MatterActorRole reviewerRole) {
        if (status != ReviewTaskStatus.PENDING) throw new IllegalStateException("REVIEW_TASK_NOT_PENDING");
        validateReviewer(reviewerId, reviewerRole);
        if (type == ReviewTaskType.LAWYER_SIGNOFF && reviewerRole != MatterActorRole.LAWYER) {
            throw new SecurityException("LAWYER_SIGNOFF_REQUIRED");
        }
        status = ReviewTaskStatus.IN_REVIEW;
        this.reviewerId = reviewerId.trim();
        version++;
    }

    public ReviewArtifactBinding artifactBinding() { return artifactBinding; }

    public static LegalReviewTask restoreBinding(LegalReviewTask task, ReviewArtifactBinding binding) {
        task.artifactBinding = binding;
        return task;
    }

    public void bindArtifact(ReviewArtifactBinding binding) {
        if (status != ReviewTaskStatus.PENDING || version != 0 || artifactBinding != null || binding == null) {
            throw new IllegalStateException("REVIEW_BINDING_IMMUTABLE");
        }
        artifactBinding = binding;
    }

    public void decide(ReviewTaskDecision decision) {
        if (status != ReviewTaskStatus.IN_REVIEW) throw new IllegalStateException("REVIEW_TASK_NOT_IN_REVIEW");
        if (decision == null) throw new IllegalArgumentException("decision is required");
        validateReviewer(decision.reviewerId(), decision.reviewerRole());
        if (decision.opinion().isBlank()) throw new IllegalArgumentException("review opinion is required");
        if (type == ReviewTaskType.LAWYER_SIGNOFF && decision.reviewerRole() != MatterActorRole.LAWYER) {
            throw new SecurityException("LAWYER_SIGNOFF_REQUIRED");
        }
        if (!reviewerId.equals(decision.reviewerId())) throw new SecurityException("REVIEW_ASSIGNEE_MISMATCH");
        if (type == ReviewTaskType.LAWYER_SIGNOFF && decision.approved() && decision.reference().isBlank()) {
            throw new IllegalArgumentException("lawyer signoff requires reference");
        }
        opinion = decision.opinion();
        decisionReference = decision.reference();
        status = decision.approved() ? ReviewTaskStatus.COMPLETED : ReviewTaskStatus.RETURNED;
        decidedAt = decision.decidedAt();
        version++;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String matterId() { return matterId; }
    public ReviewTaskType type() { return type; }
    public ReviewTaskStatus status() { return status; }
    public long version() { return version; }
    public String reviewerId() { return reviewerId; }
    public String opinion() { return opinion; }
    public String decisionReference() { return decisionReference; }
    public Instant decidedAt() { return decidedAt; }

    private void validateReviewer(String reviewer, MatterActorRole role) {
        if (reviewer == null || reviewer.isBlank() || role == null || role == MatterActorRole.SYSTEM || role == MatterActorRole.CLIENT) {
            throw new IllegalArgumentException("human reviewer and role are required");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
