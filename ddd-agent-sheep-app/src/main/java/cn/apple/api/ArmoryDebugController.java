package cn.apple.api;

import cn.apple.application.agent.AiClientRuntimeService;
import cn.apple.domain.agent.adapter.repository.IAgentRepository;
import cn.apple.domain.agent.model.valobj.AiAgentEnumVO;
import cn.apple.domain.agent.model.valobj.AiClientAdvisorVO;
import cn.apple.domain.agent.model.valobj.AiClientApiVO;
import cn.apple.domain.agent.model.valobj.AiClientModelVO;
import cn.apple.domain.agent.model.valobj.AiClientSystemPromptVO;
import cn.apple.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.apple.domain.agent.model.valobj.AiClientVO;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@Profile("dev")
@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
public class ArmoryDebugController {

    private final ApplicationContext applicationContext;
    private final IAgentRepository agentRepository;
    private final AiClientRuntimeService aiClientRuntimeService;

    @GetMapping("/armory")
    public ApiResponse<ArmorySnapshot> armory(@RequestParam("clientId") String clientId,
                                              @RequestParam(value = "assemble", defaultValue = "false") boolean assemble) {
        try {
            String checkedClientId = required(clientId, "clientId");
            String assembleError = null;
            if (assemble) {
                try {
                    aiClientRuntimeService.getChatClient(checkedClientId);
                } catch (Exception e) {
                    assembleError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
            }

            List<AiClientVO> clients = agentRepository.AiClientVOByClientIds(List.of(checkedClientId));
            AiClientVO client = clients == null || clients.isEmpty() ? null : clients.get(0);

            List<AiClientApiVO> apis = agentRepository.queryAiClientApiVOListByClientIds(List.of(checkedClientId));
            List<AiClientModelVO> models = agentRepository.AiClientModelVOByClientIds(List.of(checkedClientId));
            List<AiClientToolMcpVO> mcps = agentRepository.AiClientToolMcpVOByClientIds(List.of(checkedClientId));
            List<AiClientSystemPromptVO> prompts = agentRepository.AiClientSystemPromptVOByClientIds(List.of(checkedClientId));
            List<AiClientAdvisorVO> advisors = agentRepository.AiClientAdvisorVOByClientIds(List.of(checkedClientId));

            List<BeanItem> beans = new ArrayList<>();
            beans.add(beanItem(AiAgentEnumVO.AI_CLIENT.getBeanName(checkedClientId)));
            if (client != null && client.getModelId() != null) {
                beans.add(beanItem(AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(client.getModelId())));
            }
            if (client != null && client.getPromptIdList() != null) {
                for (String promptId : client.getPromptIdList()) {
                    if (promptId != null && !promptId.isBlank()) {
                        beans.add(beanItem(AiAgentEnumVO.AI_CLIENT_SYSTEM_PROMPT.getBeanName(promptId)));
                    }
                }
            }
            if (client != null && client.getAdvisorIdList() != null) {
                for (String advisorId : client.getAdvisorIdList()) {
                    if (advisorId != null && !advisorId.isBlank()) {
                        beans.add(beanItem(AiAgentEnumVO.AI_CLIENT_ADVISOR.getBeanName(advisorId)));
                    }
                }
            }
            if (models != null) {
                for (AiClientModelVO model : models) {
                    if (model == null) {
                        continue;
                    }
                    if (model.getApiId() != null && !model.getApiId().isBlank()) {
                        beans.add(beanItem(AiAgentEnumVO.AI_CLIENT_API.getBeanName(model.getApiId())));
                    }
                    if (model.getToolMcpIdList() != null) {
                        for (String mcpId : model.getToolMcpIdList()) {
                            if (mcpId != null && !mcpId.isBlank()) {
                                beans.add(beanItem(AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(mcpId)));
                            }
                        }
                    }
                }
            }

            return ApiResponse.ok(new ArmorySnapshot(
                    checkedClientId,
                    assemble,
                    assembleError,
                    client,
                    maskApis(apis),
                    models,
                    mcps,
                    prompts,
                    advisors,
                    beans
            ));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private List<ApiItem> maskApis(List<AiClientApiVO> apis) {
        if (apis == null || apis.isEmpty()) {
            return List.of();
        }
        return apis.stream().map(api -> new ApiItem(
                api.getApiId(),
                api.getBaseUrl(),
                api.getCompletionsPath(),
                api.getEmbeddingsPath(),
                mask(api.getApiKey())
        )).toList();
    }

    private String mask(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.length() <= 8) {
            return "****";
        }
        return v.substring(0, 4) + "****" + v.substring(v.length() - 4);
    }

    private BeanItem beanItem(String beanName) {
        boolean exists = applicationContext.containsBean(beanName);
        String type = null;
        if (exists) {
            try {
                type = applicationContext.getBean(beanName).getClass().getName();
            } catch (Exception e) {
                type = "unavailable";
            }
        }
        return new BeanItem(beanName, exists, type);
    }

    private String required(String value, String name) {
        String checked = value == null ? "" : value.trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return checked;
    }

    public record ApiItem(String apiId,
                          String baseUrl,
                          String completionsPath,
                          String embeddingsPath,
                          String apiKeyMasked) {
    }

    public record BeanItem(String beanName, boolean exists, String beanType) {
    }

    public record ArmorySnapshot(String clientId,
                                 boolean assemble,
                                 String assembleError,
                                 AiClientVO client,
                                 List<ApiItem> apis,
                                 List<AiClientModelVO> models,
                                 List<AiClientToolMcpVO> mcps,
                                 List<AiClientSystemPromptVO> prompts,
                                 List<AiClientAdvisorVO> advisors,
                                 List<BeanItem> beans) {
    }
}

