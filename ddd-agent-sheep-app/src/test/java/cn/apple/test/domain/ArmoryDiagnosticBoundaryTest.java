package cn.apple.test.domain;

import cn.apple.api.ArmoryDebugController;
import cn.apple.application.agent.AiClientRuntimeService;
import cn.apple.domain.agent.adapter.repository.IAgentRepository;
import cn.apple.domain.agent.model.valobj.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.context.support.GenericApplicationContext;
import java.lang.reflect.Proxy;
import java.util.List;
import static org.junit.Assert.*;

public class ArmoryDiagnosticBoundaryTest {
    @Test
    public void diagnosticOmitsMcpTransportSecretsAndKeyFragments() throws Exception {
        String secret = "fake-credential-123456789";
        IAgentRepository repository = (IAgentRepository) Proxy.newProxyInstance(
                IAgentRepository.class.getClassLoader(), new Class<?>[]{IAgentRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "queryAiClientApiVOListByClientIds" -> List.of(AiClientApiVO.builder()
                            .apiId("api").baseUrl("https://example.invalid").apiKey(secret).build());
                    case "AiClientToolMcpVOByClientIds" -> List.of(AiClientToolMcpVO.builder()
                            .mcpId("mcp").mcpName("test-tool").transportType("stdio")
                            .transportConfig("{env: " + secret + "}").requestTimeout(1).build());
                    default -> List.of();
                });
        AiClientRuntimeService runtime = new AiClientRuntimeService(null, null, true, 1, 3, 30000) {
            @Override
            public org.springframework.ai.chat.client.ChatClient getChatClient(String id) {
                throw new IllegalStateException(secret);
            }
        };
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            var response = new ArmoryDebugController(context, repository, runtime).armory("client", true);
            assertTrue(response.success());
            assertEquals("MODEL_ASSEMBLY_FAILED", response.data().assembleError());
            String json = new ObjectMapper().writeValueAsString(response.data());
            assertFalse(json.contains(secret));
            assertFalse(json.contains("123456789"));
            assertFalse(json.contains("transportConfig"));
            assertTrue(json.contains("test-tool"));
            assertEquals("****", response.data().apis().get(0).apiKeyMasked());
        }
    }

    @Test
    public void repositoryErrorsDoNotExposeRawMessages() {
        IAgentRepository repository = (IAgentRepository) Proxy.newProxyInstance(
                IAgentRepository.class.getClassLoader(), new Class<?>[]{IAgentRepository.class},
                (proxy, method, args) -> { throw new IllegalStateException("fake-database-credential"); });
        var response = new ArmoryDebugController(null, repository, null).armory("client", false);
        assertFalse(response.success());
        assertEquals("ARMORY_DIAGNOSTIC_FAILED", response.message());
    }
}
