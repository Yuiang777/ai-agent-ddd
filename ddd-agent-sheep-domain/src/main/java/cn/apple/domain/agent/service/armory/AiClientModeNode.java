package cn.apple.domain.agent.service.armory;

import cn.apple.domain.agent.model.entity.ArmoryCommandEntity;
import cn.apple.domain.agent.model.valobj.AiAgentEnumVO;
import cn.apple.domain.agent.model.valobj.AiClientModelVO;
import cn.apple.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.applesheep.wrench.design.framework.tree.StrategyHandler;
import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * @author _apple_sheep_
 * @description ModelNode
 * @date 2026/1/5
 * @Copyright github:Yuiang777
 */
@Slf4j
@Service
public class AiClientModeNode extends AbstractArmorySupport{

    @Resource
    private AiClientAdvisorNode aiClientAdvisorNode;
    @Override
    protected String doApply(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {


        List<AiClientModelVO> aiClientModeNodeList = dynamicContext.getValue(dataName());

        if(aiClientModeNodeList == null || aiClientModeNodeList.isEmpty()){
            log.warn("没有需要被初始化的模型");
            return router(armoryCommandEntity, dynamicContext);
        }

        //遍历模型
        for (AiClientModelVO aiClientModelVO : aiClientModeNodeList){
            OpenAiApi openAiApi = getBean(AiAgentEnumVO.AI_CLIENT_API.getBeanName(aiClientModelVO.getApiId()));


            //获取模型的tool mcp bean对象
            List<String> toolMcpIdList = aiClientModelVO.getToolMcpIdList();
            List<McpSyncClient> mcpSyncClientList = new ArrayList<>();
            if (toolMcpIdList != null) {
                for(String toolMcpId : toolMcpIdList){
                    String mcpBeanName = AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(toolMcpId);
                    if (!containsBean(mcpBeanName)) {
                        log.warn("MCP bean 未初始化，跳过模型默认工具绑定, mcpId={}", toolMcpId);
                        continue;
                    }
                    McpSyncClient mcpSyncClient = getBean(mcpBeanName);
                    mcpSyncClientList.add(mcpSyncClient);
                }
            }

            //实例化模型
            OpenAiChatModel openAiChatModel = OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(aiClientModelVO.getModelName())
                            .build())
                    .build();

            //注册模型
            registerBean(beanName(aiClientModelVO.getModelId()), OpenAiChatModel.class, openAiChatModel);
        }

        return router(armoryCommandEntity, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return aiClientAdvisorNode;
    }

    @Override
    public String beanName(String beanId){
        return AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(beanId);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT_MODEL.getDataName();
    }
}
