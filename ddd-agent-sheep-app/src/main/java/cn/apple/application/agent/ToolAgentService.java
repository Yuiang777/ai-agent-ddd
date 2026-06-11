package cn.apple.application.agent;

import cn.apple.application.governance.GovernanceAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolAgentService {

    private static final String TOOL_AGENT_SYSTEM_PROMPT = """
            你是一个法律顾问 Agent。
            你的职责是优先判断用户任务是否需要调用已挂载工具。
            如果需要外部数据、文件、搜索、业务系统或 MCP 能力，请选择合适工具执行。
            工具执行后必须整合工具结果，给出清晰、可信、可执行的结论。
            如果用户请求存在删除、破坏、越权、泄露敏感信息等风险，必须拒绝执行。
            如果当前没有合适工具，直接说明无法调用工具完成，并给出可替代建议。
            """;

    private final AiClientRuntimeService aiClientRuntimeService;
    private final GovernanceAuditService governanceAuditService;

    public ToolAgentResponse execute(ToolAgentRequest request) {
        long start = System.currentTimeMillis();
        String clientId = request == null || request.clientId() == null ? "" : request.clientId().trim();
        String message = request == null || request.message() == null ? "" : request.message().trim();
        if (clientId.isEmpty()) {
            throw new IllegalArgumentException("clientId 不能为空");
        }
        if (message.isEmpty()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (!governanceAuditService.allowRequest(clientId)) {
            governanceAuditService.recordChat(clientId, message, false, System.currentTimeMillis() - start, "tool_agent_rate_limited");
            throw new IllegalStateException("请求过于频繁，请稍后再试");
        }

        try {
            ChatClient chatClient = aiClientRuntimeService.getChatClient(clientId);
            String content = chatClient.prompt(TOOL_AGENT_SYSTEM_PROMPT + "\n\n用户任务：\n" + message).call().content();
            long costMs = System.currentTimeMillis() - start;
            governanceAuditService.recordChat(clientId, message, true, costMs, "tool_agent_ok");
            return new ToolAgentResponse(clientId, content, costMs);
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - start;
            governanceAuditService.recordChat(clientId, message, false, costMs, "tool_agent_" + e.getClass().getSimpleName());
            log.error("tool agent failed, clientId={}, costMs={}", clientId, costMs, e);
            throw new IllegalStateException(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
        }
    }

    public record ToolAgentRequest(String clientId, String message) {
    }

    public record ToolAgentResponse(String clientId, String content, long costMs) {
    }
}

