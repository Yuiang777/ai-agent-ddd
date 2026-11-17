package cn.apple.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author _apple_sheep_
 * @description API配置，值对象
 * @date 2025/12/10
 * @Copyright github:Yuiang777
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientApiVO {

    /**
     * API ID
     */
    private String apiId;

    /**
     * 基础URL
     */
    private String baseUrl;

    /**
     * API密钥
     */
    private String apiKey;

    /**
     * 对话补全路径
     */
    private String completionsPath;

    /**
     * 嵌入向量路径
     */
    private String embeddingsPath;
}
