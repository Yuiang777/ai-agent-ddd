package cn.apple.application.agent;

import cn.apple.domain.agent.model.entity.ArmoryCommandEntity;
import cn.apple.domain.agent.model.valobj.AiAgentEnumVO;
import cn.apple.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.applesheep.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.UUID;

@Slf4j
@Service
public class AiClientRuntimeService {

    private final DefaultArmoryStrategyFactory defaultArmoryStrategyFactory;
    private final ApplicationContext applicationContext;
    private final boolean modelRuntimeEnabled;
    private final int maxConcurrentPerClient;
    private final int circuitFailureThreshold;
    private final long circuitOpenMs;
    private final Map<String, Semaphore> concurrency = new ConcurrentHashMap<>();
    private final Map<String, CircuitState> circuits = new ConcurrentHashMap<>();
    // Clients can share API/model/advisor beans; serialize first-time assembly.
    private final Object assemblyMonitor = new Object();

    public AiClientRuntimeService(DefaultArmoryStrategyFactory defaultArmoryStrategyFactory,
                                  ApplicationContext applicationContext,
                                  @Value("${app.model-runtime.enabled:false}") boolean modelRuntimeEnabled,
                                  @Value("${app.model-runtime.max-concurrent-per-client:3}") int maxConcurrentPerClient,
                                  @Value("${app.model-runtime.circuit-failure-threshold:3}") int circuitFailureThreshold,
                                  @Value("${app.model-runtime.circuit-open-ms:30000}") long circuitOpenMs) {
        this.defaultArmoryStrategyFactory = defaultArmoryStrategyFactory;
        this.applicationContext = applicationContext;
        this.modelRuntimeEnabled = modelRuntimeEnabled;
        this.maxConcurrentPerClient = Math.max(1, Math.min(20, maxConcurrentPerClient));
        this.circuitFailureThreshold = Math.max(2, circuitFailureThreshold);
        this.circuitOpenMs = Math.max(1000L, circuitOpenMs);
    }

    public ChatClient getChatClient(String clientId) throws Exception {
        ensureRuntimeEnabled();
        String checkedClientId = required(clientId, "clientId");
        String clientBeanName = AiAgentEnumVO.AI_CLIENT.getBeanName(checkedClientId);
        ensureClient(checkedClientId, clientBeanName);
        return (ChatClient) applicationContext.getBean(clientBeanName);
    }

    public String call(String clientId, Prompt prompt) throws Exception {
        String conversationId = isolatedConversationId("prompt");
        return invoke(clientId, ModelInvocationPolicy.readOnly("prompt"), client -> client.prompt(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call().content());
    }

    public String invokeText(String clientId, String operation, String prompt) throws Exception {
        return invokeText(clientId, operation, prompt, isolatedConversationId(operation));
    }

    public String invokeText(String clientId, String operation, String prompt, String conversationId) throws Exception {
        String checkedConversationId = required(conversationId, "conversationId");
        return invoke(clientId, ModelInvocationPolicy.readOnly(operation), client -> client.prompt(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, checkedConversationId))
                .call().content());
    }

    public String invokeToolText(String clientId, String operation, String prompt) throws Exception {
        String conversationId = isolatedConversationId(operation);
        return invoke(clientId, ModelInvocationPolicy.toolCall(operation), client -> client.prompt(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call().content());
    }

    public RuntimeStatus status() {
        return new RuntimeStatus(modelRuntimeEnabled, maxConcurrentPerClient, circuitFailureThreshold, circuitOpenMs);
    }

    private String invoke(String clientId,
                          ModelInvocationPolicy policy,
                          Function<ChatClient, String> invocation) throws Exception {
        String checkedClientId = required(clientId, "clientId");
        ensureRuntimeEnabled();
        CircuitState circuit = circuits.computeIfAbsent(checkedClientId, ignored -> new CircuitState());
        long now = System.currentTimeMillis();
        if (circuit.openUntilMs > now) {
            throw new IllegalStateException("MODEL_CIRCUIT_OPEN");
        }

        Semaphore semaphore = concurrency.computeIfAbsent(checkedClientId, ignored -> new Semaphore(maxConcurrentPerClient));
        if (!semaphore.tryAcquire()) {
            throw new IllegalStateException("MODEL_CONCURRENCY_LIMIT");
        }
        try {
            Exception lastError = null;
            int attempts = policy == null ? 1 : policy.normalizedAttempts();
            for (int attempt = 1; attempt <= attempts; attempt++) {
                // A preceding failure or concurrent request may have opened the circuit.
                if (circuit.openUntilMs > System.currentTimeMillis()) {
                    throw new IllegalStateException("MODEL_CIRCUIT_OPEN");
                }
                try {
                    String content = invocation.apply(getChatClient(checkedClientId));
                    if (content == null || content.isBlank()) {
                        throw new IllegalStateException("MODEL_EMPTY_RESPONSE");
                    }
                    circuit.reset();
                    return content;
                } catch (Exception error) {
                    lastError = error;
                    int failures = circuit.failures.incrementAndGet();
                    if (failures >= circuitFailureThreshold) {
                        circuit.openUntilMs = System.currentTimeMillis() + circuitOpenMs;
                    }
                    if (attempt >= attempts || !retryable(error)) {
                        throw error;
                    }
                    log.warn("model invocation retry, clientId={}, operation={}, attempt={}, error={}",
                            checkedClientId, policy.operation(), attempt, error.getClass().getSimpleName());
                }
            }
            throw lastError == null ? new IllegalStateException("MODEL_INVOCATION_FAILED") : lastError;
        } finally {
            semaphore.release();
        }
    }

    private boolean retryable(Exception error) {
        String name = error.getClass().getSimpleName().toLowerCase();
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase();
        return name.contains("timeout") || name.contains("resourceaccess")
                || message.contains("timeout") || message.contains("temporarily unavailable")
                || message.contains("429") || message.contains("502") || message.contains("503");
    }

    private void ensureRuntimeEnabled() {
        if (!modelRuntimeEnabled) {
            throw new IllegalStateException("MODEL_RUNTIME_DISABLED");
        }
    }

    private void ensureClient(String clientId, String clientBeanName) throws Exception {
        if (applicationContext.containsBean(clientBeanName)) {
            return;
        }
        synchronized (assemblyMonitor) {
            if (applicationContext.containsBean(clientBeanName)) {
                return;
            }
            assembleClient(clientId, clientBeanName);
        }
    }

    private void assembleClient(String clientId, String clientBeanName) throws Exception {
        StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> handler =
                defaultArmoryStrategyFactory.armoryStrategyHandler();
        handler.apply(
                ArmoryCommandEntity.builder()
                        .commandType(AiAgentEnumVO.AI_CLIENT.getCode())
                        .commandIdList(List.of(clientId))
                        .build(),
                new DefaultArmoryStrategyFactory.DynamicContext()
        );
        if (!applicationContext.containsBean(clientBeanName)) {
            throw new IllegalStateException("客户端未装配成功，请检查 ai_client/ai_client_config/ai_client_model/ai_client_api 配置是否齐全，以及 MCP/Advisor 是否可用");
        }
        log.info("client assembled, clientId={}, beanName={}", clientId, clientBeanName);
    }

    private String required(String value, String name) {
        String checked = value == null ? "" : value.trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        if (checked.length() > 128) {
            throw new IllegalArgumentException(name + " 过长");
        }
        return checked;
    }

    private String isolatedConversationId(String operation) {
        String scope = operation == null || operation.isBlank() ? "model-call" : operation.trim();
        return "isolated:" + scope + ":" + UUID.randomUUID();
    }

    public record RuntimeStatus(boolean enabled,
                                int maxConcurrentPerClient,
                                int circuitFailureThreshold,
                                long circuitOpenMs) {
    }

    private static final class CircuitState {
        private final AtomicInteger failures = new AtomicInteger();
        private volatile long openUntilMs;

        private void reset() {
            failures.set(0);
            openUntilMs = 0L;
        }
    }
}
