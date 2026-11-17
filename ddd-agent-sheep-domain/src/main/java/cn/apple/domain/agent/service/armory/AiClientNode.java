package cn.apple.domain.agent.service.armory;

import cn.apple.domain.agent.model.entity.ArmoryCommandEntity;
import cn.apple.domain.agent.model.valobj.AiAgentEnumVO;
import cn.apple.domain.agent.model.valobj.AiClientSystemPromptVO;
import cn.apple.domain.agent.model.valobj.AiClientVO;
import cn.apple.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.applesheep.wrench.design.framework.tree.StrategyHandler;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author _apple_sheep_
 * @description
 * @date 2026/2/19
 * @Copyright github:Yuiang777
 */
@Slf4j
@Service
public class AiClientNode extends AbstractArmorySupport{
    @Override
    protected String doApply(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        List<AiClientVO> AiClientVOList = dynamicContext.getValue(dataName());

        if(AiClientVOList == null || AiClientVOList.isEmpty()){
            log.info("没有需要被初始化的客户端");
            return router(armoryCommandEntity, dynamicContext);
        }

        Map<String, AiClientSystemPromptVO> aiClientSystemPromptMap = dynamicContext.getValue(AiAgentEnumVO.AI_CLIENT_SYSTEM_PROMPT.getDataName());

        for(AiClientVO aiClientVO : AiClientVOList){

            //1.获取模型
            OpenAiChatModel openAiChatModel = getBean(aiClientVO.getModelBeanName());

            //2.设置预设话术
            StringBuffer systemPrompt = new StringBuffer("Ai 智能体 \r\n");
            List<String> promptIdList = aiClientVO.getPromptIdList();
            for(String promptId : promptIdList){
                AiClientSystemPromptVO aiClientSystemPromptVO = aiClientSystemPromptMap.get(promptId);
                systemPrompt.append(aiClientSystemPromptVO.getPromptContent());
            }

            //3.MCP服务
            List<McpSyncClient> mcpSyncClientList = new ArrayList<>();
            List<String> mcpBeanNameList = aiClientVO.getMcpBeanNameList();
            for(String mcpBeanName : mcpBeanNameList){
                mcpSyncClientList.add(getBean(mcpBeanName));
            }

            //4.Advisor服务
            List<Advisor> advisorList = new ArrayList<>();
            List<String> advisorBeanNameList = aiClientVO.getAdvisorBeanNameList();
            for(String advisorBeanName : advisorBeanNameList){
                advisorList.add(getBean(advisorBeanName));
            }

            Advisor[] advisors = advisorList.toArray(new Advisor[]{});


            ChatClient.Builder chatClientBuilder = ChatClient.builder(openAiChatModel)
                    .defaultSystem(systemPrompt.toString())
                    .defaultAdvisors(advisors);
            if (!mcpSyncClientList.isEmpty()) {
                chatClientBuilder.defaultToolCallbacks(new SyncMcpToolCallbackProvider(mcpSyncClientList.toArray(new McpSyncClient[]{})));
            }
            ChatClient chatClient = chatClientBuilder.build();

            registerBean(beanName(aiClientVO.getClientId()), ChatClient.class, chatClient);
        }

        return router(armoryCommandEntity, dynamicContext);


    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }

    @Override
    protected String beanName(String id) {
        return AiAgentEnumVO.AI_CLIENT.getBeanName(id);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT.getDataName();
    }
}
