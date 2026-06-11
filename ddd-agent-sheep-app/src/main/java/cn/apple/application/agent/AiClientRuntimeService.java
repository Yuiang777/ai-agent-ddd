package cn.apple.application.agent;

import cn.apple.domain.agent.model.entity.ArmoryCommandEntity;
import cn.apple.domain.agent.model.valobj.AiAgentEnumVO;
import cn.apple.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.applesheep.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class AiClientRuntimeService {

    private final DefaultArmoryStrategyFactory defaultArmoryStrategyFactory;
    private final ApplicationContext applicationContext;

    public AiClientRuntimeService(DefaultArmoryStrategyFactory defaultArmoryStrategyFactory, ApplicationContext applicationContext) {
        this.defaultArmoryStrategyFactory = defaultArmoryStrategyFactory;
        this.applicationContext = applicationContext;
    }

    public ChatClient getChatClient(String clientId) throws Exception {
        String checkedClientId = required(clientId, "clientId");
        String clientBeanName = AiAgentEnumVO.AI_CLIENT.getBeanName(checkedClientId);
        ensureClient(checkedClientId, clientBeanName);
        return (ChatClient) applicationContext.getBean(clientBeanName);
    }

    public String call(String clientId, Prompt prompt) throws Exception {
        return getChatClient(clientId).prompt(prompt).call().content();
    }

    private void ensureClient(String clientId, String clientBeanName) throws Exception {
        if (applicationContext.containsBean(clientBeanName)) {
            return;
        }
        StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> handler =
                defaultArmoryStrategyFactory.armoryStrategyHandler();
        handler.apply(
                ArmoryCommandEntity.builder()
                        .commandType(AiAgentEnumVO.AI_CLIENT.getCode())
                        .commandIdList(List.of(clientId))
                        .build(),
                new DefaultArmoryStrategyFactory.DynamicContext()
        );
        if (!applicationContext.containsBean(clientBeanName)) {
            throw new IllegalStateException("客户端未装配成功，请检查 ai_client/ai_client_config/ai_client_model/ai_client_api 配置是否齐全，以及 MCP/Advisor 是否可用");
        }
        log.info("client assembled, clientId={}, beanName={}", clientId, clientBeanName);
    }

    private String required(String value, String name) {
        String checked = value == null ? "" : value.trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return checked;
    }
}

