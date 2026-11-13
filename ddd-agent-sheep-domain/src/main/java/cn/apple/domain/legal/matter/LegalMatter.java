package cn.apple.domain.legal.matter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Minimal matter aggregate. Persistence adapters can rehydrate it from the
 * scalar fields and append-only transition log without coupling the domain to
 * a database or workflow engine.
 */
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
public final class LegalMatter {
    private final String id;
    private final String tenantId;
    private final String title;
    private final String clientReference;
    private final Map<String, String> attributes;
    private MatterStatus status;
    private long version;
    private Instant updatedAt;
    private final List<MatterTransition> transitions = new ArrayList<>();
    private final List<MatterArtifact> artifacts = new ArrayList<>();

    private LegalMatter(String id, String tenantId, String title, String clientReference,
                        Map<String, String> attributes, MatterStatus status, long version, Instant updatedAt) {
        this.id = required(id, "id");
        this.tenantId = required(tenantId, "tenantId");
        this.title = required(title, "title");
        this.clientReference = required(clientReference, "clientReference");
        Map<String, String> normalized = new LinkedHashMap<>();
        if (attributes != null) {
            attributes.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                    normalized.put(key.trim(), value.trim());
                }
            });
        }
        this.attributes = Map.copyOf(normalized);
        this.status = Objects.requireNonNull(status, "status");
        if (version < 0) throw new IllegalArgumentException("version must be >= 0");
        this.version = version;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static LegalMatter open(String tenantId, String title, String clientReference, Instant now) {
        return open(tenantId, title, clientReference, Map.of(), now);
    }

    public static LegalMatter open(String tenantId, String title, String clientReference,
                                   Map<String, String> attributes, Instant now) {
        return new LegalMatter(UUID.randomUUID().toString(), tenantId, title, clientReference, attributes,
                MatterStatus.INTAKE, 0, now == null ? Instant.now() : now);
    }

    public static LegalMatter rehydrate(String id, String tenantId, String title, String clientReference,
                                        MatterStatus status, long version, Instant updatedAt) {
        return new LegalMatter(id, tenantId, title, clientReference, Map.of(), status, version, updatedAt);
    }

    public static LegalMatter rehydrate(String id, String tenantId, String title, String clientReference,
                                        Map<String, String> attributes, MatterStatus status, long version,
                                        Instant updatedAt, List<MatterTransition> history) {
        LegalMatter matter = new LegalMatter(id, tenantId, title, clientReference, attributes, status, version, updatedAt);
        matter.transitions.addAll(history == null ? List.of() : List.copyOf(history));
        return matter;
    }

    public MatterTransition transitionTo(MatterStatus target, MatterTransitionCommand command) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(command, "command");
        if (command.expectedVersion() != null && command.expectedVersion() != version) {
            throw new IllegalStateException("MATTER_VERSION_CONFLICT");
        }
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("INVALID_MATTER_TRANSITION:" + status + "->" + target);
        }
        if (command.actorId().isBlank() || command.actorRole() == null) {
            throw new IllegalArgumentException("actorId and actorRole are required");
        }
        if ((target == MatterStatus.SIGNOFF_REQUIRED || target == MatterStatus.REVIEW_REQUIRED)
                && command.reason().isBlank()) {
            throw new IllegalArgumentException("review transition requires a reason");
        }
        if (target == MatterStatus.SIGNED_OFF) {
            if (command.actorRole() != MatterActorRole.LAWYER) {
                throw new SecurityException("LAWYER_SIGNOFF_REQUIRED");
            }
            if (command.reference().isBlank() || command.reason().isBlank()) {
                throw new IllegalArgumentException("lawyer signoff requires reference and opinion");
            }
        }
        if (target == MatterStatus.CLOSED) {
            if (status != MatterStatus.SIGNED_OFF) {
                throw new IllegalStateException("MATTER_MUST_BE_SIGNED_OFF_BEFORE_CLOSING");
            }
            if (command.actorRole() != MatterActorRole.LAWYER) {
                throw new SecurityException("LAWYER_ARCHIVE_REQUIRED");
            }
            if (command.reason().isBlank() || command.reference().isBlank()) {
                throw new IllegalArgumentException("archive requires reason and record reference");
            }
        }
        long nextVersion = version + 1;
        MatterTransition event = new MatterTransition(nextVersion, status, target, command.actorId(),
                command.actorRole(), command.reason(), command.reference(), command.occurredAt());
        status = target;
        version = nextVersion;
        updatedAt = command.occurredAt();
        transitions.add(event);
        return event;
    }

    public List<MatterTransition> transitionHistory() {
        return List.copyOf(transitions);
    }

    public List<MatterArtifact> artifacts() { return List.copyOf(artifacts); }

    public static LegalMatter restoreArtifacts(LegalMatter matter, List<MatterArtifact> artifacts) {
        if (artifacts != null) {
            long expected = 1;
            for (MatterArtifact artifact : artifacts) {
                if (artifact.version() != expected++) throw new IllegalArgumentException("INVALID_ARTIFACT_HISTORY");
                matter.artifacts.add(artifact);
            }
        }
        return matter;
    }

    public MatterArtifact publishArtifact(String content, String workflowRunId, String actorId,
                                          long expectedVersion, Instant now) {
        if (version != expectedVersion) throw new IllegalStateException("MATTER_VERSION_CONFLICT");
        if (status != MatterStatus.ANALYSIS && status != MatterStatus.REVIEW_REQUIRED
                && status != MatterStatus.SIGNOFF_REQUIRED) throw new IllegalStateException("ARTIFACT_PUBLICATION_NOT_ALLOWED");
        MatterArtifact artifact = new MatterArtifact(artifacts.size() + 1L, content, MatterArtifact.digest(content),
                workflowRunId, actorId, now);
        artifacts.add(artifact);
        version++;
        updatedAt = now;
        return artifact;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String title() { return title; }
    public String clientReference() { return clientReference; }
    public Map<String, String> attributes() { return attributes; }
    public MatterStatus status() { return status; }
    public long version() { return version; }
    public Instant updatedAt() { return updatedAt; }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
