package cn.apple.domain.legal.matter;

import java.time.Instant;

/** Command envelope used by API/application layers for optimistic transitions. */
public record MatterTransitionCommand(String actorId,
                                      MatterActorRole actorRole,
                                      String reason,
                                      String reference,
                                      Long expectedVersion,
                                      Instant occurredAt) {
    public MatterTransitionCommand {
        actorId = actorId == null ? "" : actorId.trim();
        reason = reason == null ? "" : reason.trim();
        reference = reference == null ? "" : reference.trim();
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    public static MatterTransitionCommand system(String reason, Instant occurredAt) {
        return new MatterTransitionCommand("system", MatterActorRole.SYSTEM, reason, "", null, occurredAt);
    }
}
