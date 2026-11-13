package cn.apple.config;

import cn.apple.api.ApiResponse;
import cn.apple.application.workflow.LitigationUploadAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class LitigationUploadAuthorizationFilter extends OncePerRequestFilter {
    private static final String UPLOAD_URI = "/api/workflows/run/stream/upload";

    private final LitigationUploadAuthorizationService uploadAuthorizationService;
    private final ObjectMapper objectMapper;

    public LitigationUploadAuthorizationFilter(LitigationUploadAuthorizationService uploadAuthorizationService,
                                                ObjectMapper objectMapper) {
        this.uploadAuthorizationService = uploadAuthorizationService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String expectedUri = request.getContextPath() + UPLOAD_URI;
        return !"POST".equalsIgnoreCase(request.getMethod()) || !expectedUri.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = request.getHeader(LitigationUploadAuthorizationService.HEADER);
        if (!uploadAuthorizationService.isActive(token)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error("案件材料上传需要有效的受理预检授权")));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
