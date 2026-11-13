package cn.apple.application.legal.matter;

import cn.apple.domain.legal.matter.repository.LegalMatterRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Offline persistence until a tenant-aware SQL/object-store adapter is installed. */
@Configuration
@ConditionalOnProperty(name = "app.infrastructure.enabled", havingValue = "false", matchIfMissing = true)
public class LegalMatterStoreConfig {
    @Bean
    @ConditionalOnProperty(name = "app.legal.matter-store", havingValue = "memory", matchIfMissing = true)
    public LegalMatterRepository legalMatterRepository() {
        return new InMemoryLegalMatterRepository();
    }
}
