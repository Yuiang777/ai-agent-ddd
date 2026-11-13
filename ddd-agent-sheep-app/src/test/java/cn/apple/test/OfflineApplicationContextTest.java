package cn.apple.test;

import cn.apple.Application;
import cn.apple.application.agent.AiClientRuntimeService;
import cn.apple.application.governance.EnterpriseReadinessService;
import cn.apple.domain.legal.adapter.repository.ILegalEvidenceRepository;
import cn.apple.domain.legal.model.valobj.LegalRetrievalRequest;
import org.junit.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class OfflineApplicationContextTest {

    @Test
    public void shouldLoadOfflineWithoutExternalInfrastructure() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class)
                .profiles("offline")
                .web(WebApplicationType.NONE)
                .properties(
                        "app.infrastructure.enabled=false",
                        "app.model-runtime.enabled=false",
                        "spring.main.lazy-initialization=false"
                )
                .run()) {
            EnterpriseReadinessService.ReadinessReport report = context
                    .getBean(EnterpriseReadinessService.class)
                    .report();
            assertEquals("OFFLINE_SAFE", report.status());
            assertFalse(report.readyForAutomatedLegalReasoning());

            AiClientRuntimeService runtime = context.getBean(AiClientRuntimeService.class);
            IllegalStateException modelError = assertThrows(IllegalStateException.class,
                    () -> runtime.invokeText("client", "test", "prompt"));
            assertEquals("MODEL_RUNTIME_DISABLED", modelError.getMessage());

            ILegalEvidenceRepository repository = context.getBean(ILegalEvidenceRepository.class);
            IllegalStateException retrievalError = assertThrows(IllegalStateException.class,
                    () -> repository.vectorSearch(new LegalRetrievalRequest("query", null, 6, 0, 0)));
            assertEquals("VECTOR_STORE_DISABLED", retrievalError.getMessage());
        }
    }
}
