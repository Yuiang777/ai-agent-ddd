package cn.apple.test.domain;

import cn.apple.domain.agent.service.memory.CompactingChatMemory;
import cn.apple.domain.agent.model.valobj.AiClientAdvisorTypeEnumVO;
import cn.apple.domain.agent.model.valobj.AiClientAdvisorVO;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CompactingChatMemoryTest {

    @Test
    public void shouldSummarizeOldTurnsAndKeepRecentMessages() {
        CompactingChatMemory memory = new CompactingChatMemory(6, 1000, 300, 3);
        for (int i = 1; i <= 8; i++) {
            memory.add("case-1", new UserMessage("用户问题" + i + "：" + "事实".repeat(35)));
            memory.add("case-1", new AssistantMessage("助手回答" + i + "：" + "分析".repeat(35)));
        }

        List<Message> messages = memory.get("case-1");

        assertTrue(messages.size() <= 6);
        assertTrue(messages.get(0) instanceof SystemMessage);
        assertTrue(messages.get(0).getText().startsWith(CompactingChatMemory.SUMMARY_PREFIX));
        assertTrue(messages.get(0).getText().contains("用户"));
        assertTrue(messages.stream().anyMatch(message -> message.getText().contains("助手回答8")));
        assertTrue(totalCharacters(messages) <= 1000);
    }

    @Test
    public void shouldBoundOversizedRecentTurnsAndIsolateConversations() {
        CompactingChatMemory memory = new CompactingChatMemory(6, 1000, 300, 3);
        memory.add("case-1", new UserMessage("甲".repeat(1800)));
        memory.add("case-1", new AssistantMessage("乙".repeat(1800)));
        memory.add("case-2", new UserMessage("独立会话"));

        List<Message> first = memory.get("case-1");

        assertTrue(totalCharacters(first) <= 1000);
        assertTrue(first.stream().anyMatch(message -> message.getText().contains("[已压缩]")));
        assertEquals(1, memory.get("case-2").size());
        assertEquals("独立会话", memory.get("case-2").get(0).getText());

        memory.clear("case-1");
        assertTrue(memory.get("case-1").isEmpty());
        assertEquals(1, memory.get("case-2").size());
    }

    @Test
    public void shouldEvictLeastRecentlyUsedConversationAndAllowMemoryWithoutVectorStore() {
        CompactingChatMemory memory = new CompactingChatMemory(6, 1000, 300, 3, 10, 3600);
        for (int i = 0; i <= 10; i++) {
            memory.add("case-" + i, new UserMessage("message-" + i));
        }

        assertTrue(memory.get("case-0").isEmpty());
        assertEquals("message-10", memory.get("case-10").get(0).getText());

        AiClientAdvisorVO advisor = AiClientAdvisorVO.builder()
                .advisorType("CHAT_MEMORY")
                .chatMemory(AiClientAdvisorVO.ChatMemory.builder().maxMessages(8).build())
                .build();
        assertNotNull(AiClientAdvisorTypeEnumVO.CHAT_MEMORY.createAdvisor(advisor, null, null));
    }

    private int totalCharacters(List<Message> messages) {
        return messages.stream().mapToInt(message -> message.getText().length()).sum();
    }
}
