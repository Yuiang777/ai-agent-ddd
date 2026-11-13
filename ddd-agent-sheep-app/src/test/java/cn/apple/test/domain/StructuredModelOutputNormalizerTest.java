package cn.apple.test.domain;

import cn.apple.application.agent.AiClientRuntimeService;
import cn.apple.application.workflow.StructuredModelOutputNormalizer;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StructuredModelOutputNormalizerTest {

    @Test
    public void shouldPreserveValidObjectWithoutRepair() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        StructuredModelOutputNormalizer normalizer = new StructuredModelOutputNormalizer(runtime);

        StructuredModelOutputNormalizer.NormalizedOutput result = normalizer.normalizeObject(
                "legal", "prefix {\"intent\":\"咨询\"} suffix", "repair", Map.of(), Set.of("intent"));

        assertEquals("{\"intent\":\"咨询\"}", result.json());
        assertTrue(result.valid());
        assertFalse(result.repaired());
        assertFalse(result.fallbackUsed());
        assertEquals(0, runtime.invocations);
    }

    @Test
    public void shouldExtractWholeArrayInsteadOfOnlyInnerObject() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        StructuredModelOutputNormalizer normalizer = new StructuredModelOutputNormalizer(runtime);

        StructuredModelOutputNormalizer.NormalizedOutput result = normalizer.normalizeArray(
                "legal", "result: [{\"title\":\"A\"},{\"title\":\"B\"}]", "repair", List.of(), Set.of("title"));

        assertTrue(result.valid());
        assertEquals(2, normalizer.parseArray(result.json()).size());
        assertEquals(0, runtime.invocations);
    }

    @Test
    public void shouldExposeRepairAndFallbackQuality() throws Exception {
        FakeRuntime repairedRuntime = new FakeRuntime("{\"intent\":\"其他\"}");
        StructuredModelOutputNormalizer normalizer = new StructuredModelOutputNormalizer(repairedRuntime);
        StructuredModelOutputNormalizer.NormalizedOutput repaired = normalizer.normalizeObject(
                "legal", "invalid", "repair", Map.of("quality", "MODEL_OUTPUT_INVALID"), Set.of("intent"));
        assertTrue(repaired.valid());
        assertTrue(repaired.repaired());
        assertFalse(repaired.fallbackUsed());

        FakeRuntime fallbackRuntime = new FakeRuntime("still invalid");
        StructuredModelOutputNormalizer fallbackNormalizer = new StructuredModelOutputNormalizer(fallbackRuntime);
        StructuredModelOutputNormalizer.NormalizedOutput fallback = fallbackNormalizer.normalizeObject(
                "legal", "invalid", "repair", Map.of("quality", "MODEL_OUTPUT_INVALID"), Set.of("intent"));
        assertFalse(fallback.valid());
        assertFalse(fallback.repaired());
        assertTrue(fallback.fallbackUsed());
        assertEquals("MODEL_OUTPUT_INVALID", fallbackNormalizer.parseObject(fallback.json()).get("quality"));
    }

    @Test
    public void shouldFallbackWhenRepairInvocationFails() throws Exception {
        StructuredModelOutputNormalizer normalizer = new StructuredModelOutputNormalizer(
                new FakeRuntimeFailure());

        StructuredModelOutputNormalizer.NormalizedOutput result = normalizer.normalizeObject(
                "legal", "invalid", "repair", Map.of("quality", "MODEL_OUTPUT_INVALID"), Set.of("intent"));

        assertFalse(result.valid());
        assertFalse(result.repaired());
        assertTrue(result.fallbackUsed());
        assertEquals("MODEL_OUTPUT_INVALID", normalizer.parseObject(result.json()).get("quality"));
    }

    private static final class FakeRuntime extends AiClientRuntimeService {
        private final Queue<String> outputs = new ArrayDeque<>();
        private int invocations;

        private FakeRuntime(String... outputs) {
            super(null, null, true, 1, 2, 1000);
            this.outputs.addAll(List.of(outputs));
        }

        @Override
        public String invokeText(String clientId, String operation, String prompt) {
            invocations++;
            return outputs.remove();
        }
    }

    private static final class FakeRuntimeFailure extends AiClientRuntimeService {
        private FakeRuntimeFailure() {
            super(null, null, true, 1, 2, 1000);
        }

        @Override
        public String invokeText(String clientId, String operation, String prompt) throws Exception {
            throw new IllegalStateException("repair unavailable");
        }
    }
}
