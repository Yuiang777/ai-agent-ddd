package cn.apple.domain.agent.model.valobj;

import cn.apple.domain.agent.service.armory.factory.element.RagAnswerAdvisor;
import cn.apple.domain.agent.service.memory.CompactingChatMemory;
import cn.apple.domain.legal.service.LegalHybridRetrievalService;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.HashMap;
import java.util.Map;

/**
 * @author _apple_sheep_
 * @description
 * @date 2026/2/13
 * @Copyright github:Yuiang777
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum  AiClientAdvisorTypeEnumVO {

    CHAT_MEMORY("CHAT_MEMORY", "上下文记忆"){
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore, LegalHybridRetrievalService legalHybridRetrievalService) {
            AiClientAdvisorVO.ChatMemory chatMemory = aiClientAdvisorVO.getChatMemory();
            int maxMessages = positive(chatMemory == null ? 0 : chatMemory.getMaxMessages(), 40);
            int maxCharacters = positive(chatMemory == null ? 0 : chatMemory.getMaxCharacters(), 12000);
            int summaryCharacters = positive(chatMemory == null ? 0 : chatMemory.getSummaryCharacters(), 2400);
            int recentMessages = positive(chatMemory == null ? 0 : chatMemory.getRecentMessages(), 12);
            int maxConversations = positive(chatMemory == null ? 0 : chatMemory.getMaxConversations(), 1000);
            long conversationTtlSeconds = positive(chatMemory == null ? 0 : chatMemory.getConversationTtlSeconds(), 3600L);
            return PromptChatMemoryAdvisor.builder(
                    new CompactingChatMemory(maxMessages, maxCharacters, summaryCharacters, recentMessages,
                            maxConversations, conversationTtlSeconds)
            ).build();
        }
    },
    RAG_ANSWER("RAG_ANSWER", "知识库") {
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore, LegalHybridRetrievalService legalHybridRetrievalService) {
            if (vectorStore == null) {
                throw new IllegalStateException("vector store is disabled; enable app.infrastructure.enabled before assembling RAG advisors");
            }
            AiClientAdvisorVO.RagAnswer ragAnswer = aiClientAdvisorVO.getRagAnswer();
            int topK = ragAnswer == null || ragAnswer.getTopK() <= 0 ? 4 : ragAnswer.getTopK();
            String filterExpression = ragAnswer == null ? null : ragAnswer.getFilterExpression();
            return new RagAnswerAdvisor(vectorStore, legalHybridRetrievalService, SearchRequest.builder()
                    .topK(topK)
                    .filterExpression(filterExpression)
                    .build());
        }
    };

    private String code;
    private String info;

    public abstract Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore, LegalHybridRetrievalService legalHybridRetrievalService);


    private static final Map<String, AiClientAdvisorTypeEnumVO> CODE_MAP = new HashMap<>();

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    static {
        for (AiClientAdvisorTypeEnumVO value : values()) {
            CODE_MAP.put(value.code, value);
        }
    }

    /**
     * 根据code获取枚举
     * @param code 编码
     * @return 枚举对象
     */
    public static AiClientAdvisorTypeEnumVO getByCode(String code) {
        AiClientAdvisorTypeEnumVO enumVO = CODE_MAP.get(code);
        if (enumVO == null) {
            throw new RuntimeException("err! advisorType " + code + " not exist!");
        }
        return enumVO;
    }
}
