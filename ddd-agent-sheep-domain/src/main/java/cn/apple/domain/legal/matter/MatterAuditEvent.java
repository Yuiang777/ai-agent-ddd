package cn.apple.domain.legal.matter;

import java.time.Instant;
import java.util.Map;

/**
 * Append-only audit record for operations that do not change the matter status
 * (evidence custody, deadline verification and review decisions).
 */
public record MatterAuditEvent(String eventId,
                               String tenantId,
                               String matterId,
                               String eventType,
                               String actorId,
                               MatterActorRole actorRole,
                               String correlationId,
                               String reason,
                               Map<String, Object> data,
                               Instant occurredAt) {
    public MatterAuditEvent {
        eventId = required(eventId, "eventId");
        tenantId = required(tenantId, "tenantId");
        matterId = required(matterId, "matterId");
        eventType = required(eventType, "eventType");
        actorId = required(actorId, "actorId");
        actorRole = actorRole == null ? MatterActorRole.SYSTEM : actorRole;
        correlationId = correlationId == null ? "" : correlationId.trim();
        reason = reason == null ? "" : reason.trim();
        data = data == null ? Map.of() : Map.copyOf(data);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
