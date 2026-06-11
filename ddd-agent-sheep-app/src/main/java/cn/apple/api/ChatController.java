package cn.apple.api;

import cn.apple.application.agent.AiClientRuntimeService;
import cn.apple.application.governance.GovernanceAuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 35418
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class ChatController {

    private final AiClientRuntimeService aiClientRuntimeService;
    private final GovernanceAuditService governanceAuditService;

    public ChatController(AiClientRuntimeService aiClientRuntimeService,
                          GovernanceAuditService governanceAuditService) {
        this.aiClientRuntimeService = aiClientRuntimeService;
        this.governanceAuditService = governanceAuditService;
    }

    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@RequestBody ChatRequest request) {
        long start = System.currentTimeMillis();
        String clientId = request == null || request.clientId() == null ? "" : request.clientId().trim();
        String message = request == null || request.message() == null ? "" : request.message().trim();
        if (clientId.isEmpty()) {
            return ApiResponse.error("clientId 不能为空");
        }
        if (message.isEmpty()) {
            return ApiResponse.error("message 不能为空");
        }
        if (!governanceAuditService.allowRequest(clientId)) {
            governanceAuditService.recordChat(clientId, message, false, System.currentTimeMillis() - start, "rate_limited");
            return ApiResponse.error("请求过于频繁，请稍后再试");
        }

        try {
            log.info("chat request, clientId={}, msgLen={}", clientId, message.length());
            ChatClient chatClient = aiClientRuntimeService.getChatClient(clientId);
            String content = chatClient.prompt(Prompt.builder()
                    .messages(new UserMessage(message))
                    .build()).call().content();

            long costMs = System.currentTimeMillis() - start;
            governanceAuditService.recordChat(clientId, message, true, costMs, truncate(content, 160));
            log.info("chat ok, clientId={}, costMs={}", clientId, costMs);
            return ApiResponse.ok(new ChatResponse(content));
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - start;
            governanceAuditService.recordChat(clientId, message, false, costMs, e.getClass().getSimpleName());
            log.error("chat failed, costMs={}", costMs, e);
            return ApiResponse.error(e.getMessage() == null ? e.getClass().getName() : e.getMessage());
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record ChatRequest(String clientId, String message) {
    }

    public record ChatResponse(String content) {
    }
}
