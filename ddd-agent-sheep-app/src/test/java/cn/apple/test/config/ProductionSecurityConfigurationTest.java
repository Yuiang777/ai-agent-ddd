package cn.apple.test.config;

import org.junit.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.env.YamlPropertySourceLoader;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProductionSecurityConfigurationTest {
    private StandardEnvironment productionEnvironment(Map<String, Object> overrides) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("test-overrides", overrides));
        }
        List<org.springframework.core.env.PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("production", new ClassPathResource("application-prod.yml"));
        sources.forEach(environment.getPropertySources()::addLast);
        return environment;
    }

    @Test public void productionDefaultsRequireAuthenticatedContextAndDisableTrustedHeaders() throws Exception {
        StandardEnvironment environment = productionEnvironment(Map.of());

        assertTrue(environment.getProperty("app.enterprise.security-context-enabled", Boolean.class));
        assertFalse(environment.getProperty("app.enterprise.trusted-context-header-enabled", Boolean.class));
    }

    @Test public void explicitEnvironmentOverridesRemainSupported() throws Exception {
        StandardEnvironment environment = productionEnvironment(Map.of(
                "APP_ENTERPRISE_SECURITY_CONTEXT_ENABLED", "false",
                "APP_ENTERPRISE_TRUSTED_CONTEXT_HEADER_ENABLED", "true"));

        assertFalse(environment.getProperty("app.enterprise.security-context-enabled", Boolean.class));
        assertTrue(environment.getProperty("app.enterprise.trusted-context-header-enabled", Boolean.class));
    }
}
