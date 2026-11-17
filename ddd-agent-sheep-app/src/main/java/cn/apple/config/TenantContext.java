package cn.apple.config;

import cn.apple.domain.legal.matter.MatterActorRole;

/** Request-scoped identity boundary populated by the authenticated gateway. */
public final class TenantContext {
    public static final String TENANT_ATTRIBUTE = "authenticatedTenantId";
    public static final String ACTOR_ATTRIBUTE = "authenticatedActorId";
    public static final String ROLE_ATTRIBUTE = "authenticatedActorRole";
    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();
    private TenantContext() { }
    public static void set(Principal principal) { CURRENT.set(principal); }
    public static void clear() { CURRENT.remove(); }
    public static Principal current() { return CURRENT.get(); }
    public record Principal(String tenantId, String actorId, MatterActorRole role) {
        public Principal {
            if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenant context is required");
            if (actorId == null || actorId.isBlank()) throw new IllegalArgumentException("actor context is required");
            tenantId = tenantId.trim(); actorId = actorId.trim();
        }
    }
}
