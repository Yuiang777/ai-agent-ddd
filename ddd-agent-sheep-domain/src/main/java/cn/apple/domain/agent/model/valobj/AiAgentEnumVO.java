package cn.apple.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;


/**
 * @author _apple_sheep_
 * @description AI代理枚举值对象
 * @date 2025/12/10
 */

@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum AiAgentEnumVO {

    AI_CLIENT_API("对话API", "api", "ai_client_api_", "ai_client_api_data_list", "aiClientApiLoadDataStrategy"),
    AI_CLIENT_MODEL("对话模型", "model", "ai_client_model_", "ai_client_model_data_list", "aiClientModelLoadDataStrategy"),
    AI_CLIENT_SYSTEM_PROMPT("提示词", "prompt", "ai_client_system_prompt_", "ai_client_system_prompt_data_list", "aiClientSystemPromptLoadDataStrategy"),
    AI_CLIENT_TOOL_MCP("mcp工具", "mcp", "ai_client_tool_mcp_", "ai_client_tool_mcp_data_list", "aiClientToolMCPLoadDataStrategy"),
    AI_CLIENT_ADVISOR("顾问角色", "advisor", "ai_client_advisor_", "ai_client_advisor_data_list", "aiClientAdvisorLoadDataStrategy"),
    AI_CLIENT("客户端", "client", "ai_client_", "ai_client_data_list", "aiClientLoadDataStrategy"),

    ;

    /**
     * 名称
     */
    private String name;

    /**
     * code
     */
    private String code;

    /**
     * Bean 对象名称标签
     */
    private String beanNameTag;

    /**
     * 数据名称
     */
    private String dataName;

    /**
     * 装配数据策略
     */
    private String loadDataStrategy;

    public static AiAgentEnumVO getByCode(String code) {

        if(null == code){
            return null;
        }

        for(AiAgentEnumVO aiAgentEnumVO : AiAgentEnumVO.values()){
            if(aiAgentEnumVO.code.equals(code)){
                return aiAgentEnumVO;
            }
        }

        throw new RuntimeException("未找到对应的枚举值对象");

    }


    public String getBeanName(String id) {
        return this.beanNameTag + id;
    }
}
