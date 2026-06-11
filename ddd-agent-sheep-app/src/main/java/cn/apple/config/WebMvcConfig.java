package cn.apple.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties({AdminApiProperties.class, UploadProperties.class})
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminApiProperties adminApiProperties;
    private final ObjectMapper objectMapper;

    public WebMvcConfig(AdminApiProperties adminApiProperties, ObjectMapper objectMapper) {
        this.adminApiProperties = adminApiProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminApiInterceptor(adminApiProperties, objectMapper));
    }
}
