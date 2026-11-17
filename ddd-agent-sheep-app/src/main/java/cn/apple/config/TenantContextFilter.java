package cn.apple.config;

import cn.apple.domain.legal.matter.MatterActorRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Bridges trusted gateway attributes into the application request context. */
public class TenantContextFilter extends OncePerRequestFilter {
    private final boolean securityEnabled;
    private final boolean trustedHeaderEnabled;
    public TenantContextFilter(@Value("${app.enterprise.security-context-enabled:false}") boolean securityEnabled,
                               @Value("${app.enterprise.trusted-context-header-enabled:false}") boolean trustedHeaderEnabled) {
        this.securityEnabled = securityEnabled; this.trustedHeaderEnabled = trustedHeaderEnabled;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try { TenantContext.Principal principal = principal(request); if (principal != null) TenantContext.set(principal); chain.doFilter(request, response); }
        finally { TenantContext.clear(); }
    }
    private TenantContext.Principal principal(HttpServletRequest request) {
        Object tenant = request.getAttribute(TenantContext.TENANT_ATTRIBUTE);
        Object actor = request.getAttribute(TenantContext.ACTOR_ATTRIBUTE);
        Object role = request.getAttribute(TenantContext.ROLE_ATTRIBUTE);
        if (tenant != null && actor != null) return new TenantContext.Principal(String.valueOf(tenant), String.valueOf(actor), parseRole(role));
        if (securityEnabled) {
            if (!trustedHeaderEnabled) return null;
            String headerTenant = request.getHeader("X-Authenticated-Tenant-Id");
            String headerActor = request.getHeader("X-Authenticated-Actor-Id");
            if (headerTenant == null || headerActor == null) return null;
            return new TenantContext.Principal(headerTenant, headerActor, parseRole(request.getHeader("X-Authenticated-Actor-Role")));
        }
        return new TenantContext.Principal("offline-tenant", "offline-user", MatterActorRole.PARALEGAL);
    }
    private MatterActorRole parseRole(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return securityEnabled ? null : MatterActorRole.PARALEGAL;
        try { return MatterActorRole.valueOf(String.valueOf(value).trim().toUpperCase()); }
        catch (IllegalArgumentException ignored) { return securityEnabled ? null : MatterActorRole.PARALEGAL; }
    }
}
