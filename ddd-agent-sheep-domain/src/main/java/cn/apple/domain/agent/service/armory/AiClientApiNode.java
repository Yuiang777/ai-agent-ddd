package cn.apple.domain.agent.service.armory;

import cn.apple.domain.agent.model.entity.ArmoryCommandEntity;
import cn.apple.domain.agent.model.valobj.AiAgentEnumVO;
import cn.apple.domain.agent.model.valobj.AiClientApiVO;
import cn.apple.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.applesheep.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author _apple_sheep_
 * @description
 * @date 2025/12/12
 * @Copyright github:Yuiang777
 */
@Slf4j
@Service
public class AiClientApiNode extends AbstractArmorySupport{
    @Override
    protected String doApply(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        List<AiClientApiVO> aiClientApiVOList = dynamicContext.getValue(AiAgentEnumVO.AI_CLIENT_API.getDataName());

        for(AiClientApiVO aiClientApiVO : aiClientApiVOList){
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl(aiClientApiVO.getBaseUrl())
                    .apiKey(aiClientApiVO.getApiKey())
                    .completionsPath(aiClientApiVO.getCompletionsPath())
                    .embeddingsPath(aiClientApiVO.getEmbeddingsPath())
                    .build();

            registerBean(AiAgentEnumVO.AI_CLIENT_API.getBeanName(aiClientApiVO.getApiId()), OpenAiApi.class, openAiApi);
        }
        return router(armoryCommandEntity, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }
}
