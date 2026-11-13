package cn.apple.api;

import cn.apple.application.agent.AiClientRuntimeService;
import cn.apple.application.governance.GovernanceAuditService;
import cn.apple.config.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * @author 35418
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final int MAX_MESSAGE_LENGTH = 20000;
    private static final int MAX_CONVERSATION_ID_LENGTH = 128;

    private final AiClientRuntimeService aiClientRuntimeService;
    private final GovernanceAuditService governanceAuditService;
    private final String modelClientId;

    @Autowired
    public ChatController(AiClientRuntimeService aiClientRuntimeService,
                          GovernanceAuditService governanceAuditService,
                          @Value("${chat.model-client-id:${workflow.model-client-id:law-consultant}}") String modelClientId) {
        this.aiClientRuntimeService = aiClientRuntimeService;
        this.governanceAuditService = governanceAuditService;
        this.modelClientId = modelClientId == null || modelClientId.isBlank() ? "law-consultant" : modelClientId.trim();
    }

    /** Compatibility constructor for direct unit-test callers. */
    public ChatController(AiClientRuntimeService aiClientRuntimeService, GovernanceAuditService governanceAuditService) {
        this(aiClientRuntimeService, governanceAuditService, "law-consultant");
    }

    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@RequestBody ChatRequest request) {
        long start = System.currentTimeMillis();
        // Model routing is a server-side policy. The legacy clientId field is
        // retained only for request compatibility and is never trusted.
        String clientId = modelClientId;
        String message = request == null || request.message() == null ? "" : request.message().trim();
        String conversationId = request == null || request.conversationId() == null || request.conversationId().isBlank()
                ? "default"
                : request.conversationId().trim();
        if (message.isEmpty()) {
            return ApiResponse.error("message 不能为空");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return ApiResponse.error("message 过长");
        }
        if (conversationId.length() > MAX_CONVERSATION_ID_LENGTH) {
            return ApiResponse.error("conversationId 过长");
        }
        if (!governanceAuditService.allowRequest(clientId)) {
            governanceAuditService.recordChat(clientId, message, false, System.currentTimeMillis() - start, "rate_limited");
            return ApiResponse.error("请求过于频繁，请稍后再试");
        }

        try {
            log.info("chat request, clientId={}, msgLen={}", clientId, message.length());
            String content = aiClientRuntimeService.invokeText(
                    clientId, "chat", message, scopedConversationId(conversationId));

            long costMs = System.currentTimeMillis() - start;
            governanceAuditService.recordChat(clientId, message, true, costMs, "chat_ok");
            log.info("chat ok, clientId={}, costMs={}", clientId, costMs);
            return ApiResponse.ok(new ChatResponse(content, conversationId));
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - start;
            governanceAuditService.recordChat(clientId, message, false, costMs, e.getClass().getSimpleName());
            log.error("chat failed, costMs={}, errorType={}", costMs, e.getClass().getSimpleName());
            return ApiResponse.error(safeModelError(e));
        }
    }

    // Never expose upstream exception messages/bodies: they may contain credentials or user data.
    private String safeModelError(Exception error) {
        if (error instanceof IllegalStateException) {
            String code = error.getMessage();
            if ("MODEL_RUNTIME_DISABLED".equals(code)
                    || "MODEL_CONCURRENCY_LIMIT".equals(code)
                    || "MODEL_CIRCUIT_OPEN".equals(code)) {
                return code;
            }
        }
        return "MODEL_INVOCATION_FAILED";
    }

    private String scopedConversationId(String conversationId) {
        TenantContext.Principal principal = TenantContext.current();
        String tenantId = principal == null ? "offline-tenant" : principal.tenantId();
        String actorId = principal == null ? "offline-user" : principal.actorId();
        String scope = tenantId + "\u0000" + actorId + "\u0000" + conversationId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("failed to scope conversation", e);
        }
    }

    public record ChatRequest(String clientId, String message, String conversationId) {
        public ChatRequest(String clientId, String message) {
            this(clientId, message, null);
        }
    }

    public record ChatResponse(String content, String conversationId) {
        public ChatResponse(String content) {
            this(content, null);
        }
    }
}
