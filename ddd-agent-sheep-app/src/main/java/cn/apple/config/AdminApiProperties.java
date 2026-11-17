package cn.apple.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

@ConfigurationProperties(prefix = "app.admin-api")
public class AdminApiProperties {

    private boolean enabled;
    private String token;
    private Set<String> protectedPatterns = Set.of(
            "/api/chat",
            "/api/agent/**",
            "/api/governance/**",
            "/api/legal/documents/**",
            "/api/legal/agent/**",
            "/api/legal/matters/**",
            "/api/billing/**",
            "/api/multimodal/**",
            "/api/workflows/**"
    );

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Set<String> getProtectedPatterns() {
        return protectedPatterns;
    }

    public void setProtectedPatterns(Set<String> protectedPatterns) {
        this.protectedPatterns = protectedPatterns;
    }
}
