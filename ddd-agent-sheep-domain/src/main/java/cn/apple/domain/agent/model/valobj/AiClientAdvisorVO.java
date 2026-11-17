package cn.apple.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * @author _apple_sheep_
 * @description 顾问，值对象
 * @date 2025/12/10
 */

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientAdvisorVO {

    /**
     * 顾问ID
     */
    private String advisorId;

    /**
     * 顾问名称
     */
    private String advisorName;

    /**
     * 顾问类型(PromptChatMemory/RagAnswer/SimpleLoggerAdvisor等)
     */
    private String advisorType;

    /**
     * 顺序号
     */
    private Integer orderNum;

    /**
     * 扩展；记忆
     */
    private ChatMemory chatMemory;

    /**
     * 扩展；rag 问答
     */
    private RagAnswer ragAnswer;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ChatMemory {
        private int maxMessages;
        private int maxCharacters;
        private int summaryCharacters;
        private int recentMessages;
        private int maxConversations;
        private long conversationTtlSeconds;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RagAnswer {
        private int topK = 4;
        private String filterExpression;
    }

}
