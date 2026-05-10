package cn.apple.api;

import cn.apple.domain.agent.model.entity.ArmoryCommandEntity;
import cn.apple.domain.agent.model.valobj.AiAgentEnumVO;
import cn.apple.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.applesheep.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author 35418
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class ChatController {

    private final DefaultArmoryStrategyFactory defaultArmoryStrategyFactory;
    private final ApplicationContext applicationContext;

    public ChatController(DefaultArmoryStrategyFactory defaultArmoryStrategyFactory, ApplicationContext applicationContext) {
        this.defaultArmoryStrategyFactory = defaultArmoryStrategyFactory;
        this.applicationContext = applicationContext;
    }

    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@RequestBody ChatRequest request) {
        long start = System.currentTimeMillis();
        try {
            String clientId = request == null || request.clientId() == null ? "" : request.clientId().trim();
            if (clientId.isEmpty()) {
                return ApiResponse.error("clientId 不能为空");
            }

            String message = request.message() == null ? "" : request.message().trim();
            if (message.isEmpty()) {
                return ApiResponse.error("message 不能为空");
            }

            String clientBeanName = AiAgentEnumVO.AI_CLIENT.getBeanName(clientId);
            log.info("chat request, clientId={}, beanName={}, msgLen={}", clientId, clientBeanName, message.length());

            if (!applicationContext.containsBean(clientBeanName)) {
                StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> handler =
                        defaultArmoryStrategyFactory.armoryStrategyHandler();

                handler.apply(
                        ArmoryCommandEntity.builder()
                                .commandType(AiAgentEnumVO.AI_CLIENT.getCode())
                                .commandIdList(List.of(clientId))
                                .build(),
                        new DefaultArmoryStrategyFactory.DynamicContext()
                );
            }

            if (!applicationContext.containsBean(clientBeanName)) {
                return ApiResponse.error("客户端未装配成功，请检查数据库 ai_client/ai_client_config/ai_client_model/ai_client_api 配置是否齐全");
            }

            ChatClient chatClient = (ChatClient) applicationContext.getBean(clientBeanName);
            String content = chatClient.prompt(Prompt.builder()
                    .messages(new UserMessage(message))
                    .build()).call().content();

            log.info("chat ok, clientId={}, costMs={}", clientId, System.currentTimeMillis() - start);
            return ApiResponse.ok(new ChatResponse(content));
        } catch (Exception e) {
            log.error("chat failed, costMs={}", System.currentTimeMillis() - start, e);
            return ApiResponse.error(e.getMessage() == null ? e.getClass().getName() : e.getMessage());
        }
    }

    public record ChatRequest(String clientId, String message) {
    }

    public record ChatResponse(String content) {
    }

    public record ApiResponse<T>(boolean success, T data, String message) {
        public static <T> ApiResponse<T> ok(T data) {
            return new ApiResponse<>(true, data, null);
        }

        public static <T> ApiResponse<T> error(String message) {
            return new ApiResponse<>(false, null, message);
        }
    }
}
