package cn.apple.test.domain;

import cn.apple.application.agent.ModelInvocationPolicy;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ModelInvocationPolicyTest {

    @Test
    public void shouldBoundReadOnlyRetries() {
        assertEquals(2, new ModelInvocationPolicy("reasoning", false, 20).normalizedAttempts());
        assertEquals(1, new ModelInvocationPolicy("reasoning", false, 0).normalizedAttempts());
    }

    @Test
    public void shouldNeverRetryToolCalls() {
        assertEquals(1, new ModelInvocationPolicy("mcp", true, 20).normalizedAttempts());
    }
}
