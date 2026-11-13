package cn.apple.test.domain;

import cn.apple.application.agent.AiClientRuntimeService;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClient;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ModelCircuitRetryBoundaryTest {
    private AiClientRuntimeService failingRuntime(AtomicInteger calls, int threshold) {
        return new AiClientRuntimeService(null, null, true, 1, threshold, 30000) {
            @Override
            public ChatClient getChatClient(String clientId) {
                calls.incrementAndGet();
                throw new IllegalStateException("503 upstream unavailable");
            }
        };
    }

    private String failure(ThrowingCall action) throws Exception {
        try {
            action.run();
            fail("Expected failure");
            return "";
        } catch (IllegalStateException error) {
            return error.getMessage();
        }
    }

    @Test
    public void retryMustStopWhenPreviousAttemptOpensCircuit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AiClientRuntimeService runtime = failingRuntime(calls, 2);
        assertEquals("503 upstream unavailable", failure(() -> runtime.invokeToolText("client", "tool", "hello")));
        assertEquals(1, calls.get());
        assertEquals("MODEL_CIRCUIT_OPEN", failure(() -> runtime.invokeText("client", "chat", "hello")));
        assertEquals("Retry must not invoke the model after threshold is reached", 2, calls.get());
        assertEquals("MODEL_CIRCUIT_OPEN", failure(() -> runtime.invokeText("client", "chat", "hello")));
        assertEquals(2, calls.get());
        assertEquals("503 upstream unavailable", failure(() -> runtime.invokeToolText("other-client", "tool", "hello")));
        assertEquals("Circuit state must be isolated per configured client", 3, calls.get());
    }

    @Test
    public void failedAttemptsReleaseTheOnlyConcurrencyPermit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AiClientRuntimeService runtime = failingRuntime(calls, 20);
        for (int i = 0; i < 3; i++) {
            assertEquals("503 upstream unavailable", failure(() -> runtime.invokeText("client", "chat", "hello")));
        }
        assertEquals("Read-only requests retry twice and release their permit", 6, calls.get());
        assertEquals("503 upstream unavailable", failure(() -> runtime.invokeToolText("client", "tool", "hello")));
        assertEquals("Tool requests must not be retried", 7, calls.get());
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }
}
