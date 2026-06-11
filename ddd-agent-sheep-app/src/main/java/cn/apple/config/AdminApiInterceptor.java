package cn.apple.config;

import cn.apple.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

public class AdminApiInterceptor implements HandlerInterceptor {

    private static final String HEADER = "X-Admin-Token";

    private final AdminApiProperties properties;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public AdminApiInterceptor(AdminApiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!requiresAdmin(request.getRequestURI())) {
            return true;
        }
        String expected = properties.getToken() == null ? "" : properties.getToken().trim();
        if (!properties.isEnabled() || expected.isEmpty()) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "管理接口未启用");
            return false;
        }
        String actual = request.getHeader(HEADER);
        if (!expected.equals(actual)) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "未授权访问");
            return false;
        }
        return true;
    }

    private boolean requiresAdmin(String uri) {
        return properties.getProtectedPatterns().stream().anyMatch(pattern -> pathMatcher.match(pattern, uri));
    }

    private void writeError(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(message)));
    }
}
