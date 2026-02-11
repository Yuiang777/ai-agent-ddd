package cn.apple.infrastructure.mapper;

import cn.apple.domain.agent.model.valobj.AiClientSystemPromptVO;
import cn.apple.infrastructure.persistent.po.AiClientSystemPrompt;
import org.mapstruct.Mapper;

/**
 * @author _apple_sheep_
 * @description
 * @date 2025/12/10
 * @Copyright github:Yuiang777
 */
@Mapper(componentModel = "spring")
public interface AiClientSystemPromptVO2AiClientSystemPrompt {

    AiClientSystemPromptVO toVO(AiClientSystemPrompt aiClientSystemPrompt);
    AiClientSystemPrompt toPO(AiClientSystemPromptVO aiClientSystemPromptVO);
}
