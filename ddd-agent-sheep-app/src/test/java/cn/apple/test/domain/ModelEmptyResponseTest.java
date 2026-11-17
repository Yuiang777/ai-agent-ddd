package cn.apple.test.domain;

import cn.apple.application.agent.AiClientRuntimeService;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class ModelEmptyResponseTest {
    private AiClientRuntimeService runtime(AtomicReference<String> text, AtomicInteger calls, int threshold) {
        ChatModel model = prompt -> {
            calls.incrementAndGet();
            String content = text.get();
            return content == null ? new ChatResponse(List.of())
                    : new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
        };
        ChatClient client = ChatClient.builder(model).build();
        return new AiClientRuntimeService(null, null, true, 1, threshold, 30000) {
            @Override
            public ChatClient getChatClient(String clientId) {
                return client;
            }
        };
    }

    private void assertFailure(String expected, ThrowingCall action) throws Exception {
        try {
            action.run();
            fail("Empty response must not be reported as success");
        } catch (IllegalStateException error) {
            assertEquals(expected, error.getMessage());
        }
    }

    @Test
    public void nullBlankAndWhitespaceFailAcrossAllTextEntrypointsWithoutRetry() throws Exception {
        AtomicReference<String> text = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        AiClientRuntimeService runtime = runtime(text, calls, 20);
        assertFailure("MODEL_EMPTY_RESPONSE", () -> runtime.invokeText("client", "chat", "hello"));
        text.set("");
        assertFailure("MODEL_EMPTY_RESPONSE", () -> runtime.call("client", new Prompt("hello")));
        text.set(" \n\t ");
        assertFailure("MODEL_EMPTY_RESPONSE", () -> runtime.invokeToolText("client", "tool", "hello"));
        assertEquals("Empty output must not trigger an automatic retry", 3, calls.get());
        text.set("  Valid answer\n");
        assertEquals("  Valid answer\n", runtime.invokeText("client", "chat", "hello"));
        assertEquals(4, calls.get());
    }

    @Test
    public void repeatedEmptyResponsesOpenCircuitInsteadOfResettingIt() throws Exception {
        AtomicReference<String> text = new AtomicReference<>(" ");
        AtomicInteger calls = new AtomicInteger();
        AiClientRuntimeService runtime = runtime(text, calls, 2);
        assertFailure("MODEL_EMPTY_RESPONSE", () -> runtime.invokeText("client", "chat", "hello"));
        assertFailure("MODEL_EMPTY_RESPONSE", () -> runtime.invokeText("client", "chat", "hello"));
        text.set("answer");
        assertFailure("MODEL_CIRCUIT_OPEN", () -> runtime.invokeText("client", "chat", "hello"));
        assertEquals(2, calls.get());
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }
}
