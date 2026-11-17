package cn.apple.infrastructure.legal.matter;

import cn.apple.domain.legal.matter.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Explicit versioned persistence representation; no polymorphic deserialization. */
final class MatterSnapshotCodec {
    private final ObjectMapper mapper;
    MatterSnapshotCodec(ObjectMapper mapper) { this.mapper = mapper.copy(); }

    String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("MATTER_SNAPSHOT_WRITE_FAILED", error); }
    }
    <T> T read(String payload, Class<T> type) {
        try { return mapper.readValue(payload, type); }
        catch (Exception error) { throw new IllegalStateException("MATTER_SNAPSHOT_READ_FAILED", error); }
    }
    String matter(LegalMatter item) {
        return write(new MatterState(2, item.id(), item.tenantId(), item.title(), item.clientReference(),
                item.attributes(), item.status(), item.version(), item.updatedAt(), item.transitionHistory(), item.artifacts()));
    }
    LegalMatter matter(String payload) {
        MatterState item = read(payload, MatterState.class);
        if (item.schemaVersion() != 1 && item.schemaVersion() != 2) throw new IllegalStateException("UNSUPPORTED_MATTER_SCHEMA");
        return LegalMatter.restoreArtifacts(LegalMatter.rehydrate(item.id(), item.tenantId(), item.title(), item.clientReference(),
                item.attributes(), item.status(), item.version(), item.updatedAt(), item.transitions()), item.artifacts());
    }
    String review(LegalReviewTask item) {
        return write(new ReviewState(2, item.id(), item.tenantId(), item.matterId(), item.type(), item.status(),
                item.version(), item.reviewerId(), item.opinion(), item.decisionReference(), item.decidedAt(), item.artifactBinding()));
    }
    LegalReviewTask review(String payload) {
        ReviewState item = read(payload, ReviewState.class);
        if (item.schemaVersion() != 1 && item.schemaVersion() != 2) throw new IllegalStateException("UNSUPPORTED_REVIEW_SCHEMA");
        return LegalReviewTask.restoreBinding(LegalReviewTask.rehydrate(item.id(), item.tenantId(), item.matterId(), item.type(), item.status(),
                item.version(), item.reviewerId(), item.opinion(), item.decisionReference(), item.decidedAt()), item.artifactBinding());
    }
    private record MatterState(int schemaVersion, String id, String tenantId, String title, String clientReference,
                               Map<String, String> attributes, MatterStatus status, long version, Instant updatedAt,
                               List<MatterTransition> transitions, List<MatterArtifact> artifacts) { }
    private record ReviewState(int schemaVersion, String id, String tenantId, String matterId, ReviewTaskType type,
                               ReviewTaskStatus status, long version, String reviewerId, String opinion,
                               String decisionReference, Instant decidedAt, ReviewArtifactBinding artifactBinding) { }
}
