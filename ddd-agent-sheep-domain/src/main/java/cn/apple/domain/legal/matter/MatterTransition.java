package cn.apple.domain.legal.matter;

import java.time.Instant;

/** Immutable audit event emitted for every accepted matter transition. */
public record MatterTransition(long version,
                               MatterStatus from,
                               MatterStatus to,
                               String actorId,
                               MatterActorRole actorRole,
                               String reason,
                               String reference,
                               Instant occurredAt) {
}
