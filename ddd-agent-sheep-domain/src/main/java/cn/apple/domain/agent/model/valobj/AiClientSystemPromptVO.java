package cn.apple.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author _apple_sheep_
 * @description 系统提示词配置表
 * @date 2025/12/10
 * @Copyright github:Yuiang777
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiClientSystemPromptVO {

    /**
     * 提示词ID
     */
    private String promptId;

    /**
     * 提示词名称
     */
    private String promptName;

    /**
     * 提示词内容
     */
    private String promptContent;

    /**
     * 描述
     */
    private String description;
}
