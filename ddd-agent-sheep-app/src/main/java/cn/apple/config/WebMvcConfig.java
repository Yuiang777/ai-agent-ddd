package cn.apple.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@EnableConfigurationProperties({AdminApiProperties.class, UploadProperties.class, MultipartProperties.class})
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminApiProperties adminApiProperties;
    private final ObjectMapper objectMapper;
    private final boolean securityContextEnabled;
    private final boolean trustedContextHeaderEnabled;

    public WebMvcConfig(AdminApiProperties adminApiProperties, ObjectMapper objectMapper,
                        @Value("${app.enterprise.security-context-enabled:false}") boolean securityContextEnabled,
                        @Value("${app.enterprise.trusted-context-header-enabled:false}") boolean trustedContextHeaderEnabled) {
        this.adminApiProperties = adminApiProperties;
        this.objectMapper = objectMapper;
        this.securityContextEnabled = securityContextEnabled;
        this.trustedContextHeaderEnabled = trustedContextHeaderEnabled;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminApiInterceptor(adminApiProperties, objectMapper));
    }

    @Bean
    public FilterRegistrationBean<TenantContextFilter> tenantContextFilter() {
        FilterRegistrationBean<TenantContextFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TenantContextFilter(securityContextEnabled, trustedContextHeaderEnabled));
        registration.setOrder(-100);
        return registration;
    }
}
