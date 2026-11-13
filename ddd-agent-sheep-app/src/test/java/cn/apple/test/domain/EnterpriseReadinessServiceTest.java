package cn.apple.test.domain;

import cn.apple.application.agent.AiClientRuntimeService;
import cn.apple.application.governance.EnterpriseReadinessService;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class EnterpriseReadinessServiceTest {

    @Test
    public void shouldNotClaimProductionReadinessFromFeatureFlagsAlone() {
        AiClientRuntimeService runtime = new AiClientRuntimeService(null, null, true, 2, 3, 30000);
        EnterpriseReadinessService service = new EnterpriseReadinessService(
                new MockEnvironment().withProperty("spring.profiles.active", "dev"),
                runtime,
                true,
                false,
                false);

        EnterpriseReadinessService.ReadinessReport report = service.report();

        assertEquals("VALIDATION_REQUIRED", report.status());
        assertFalse(report.readyForAutomatedLegalReasoning());
        assertEquals("CONFIGURED_UNVERIFIED", report.capabilities().get("mysql_configuration").status());
        assertEquals("PLANNED", report.capabilities().get("tenant_knowledge_isolation").status());
        assertEquals("DISABLED", report.capabilities().get("automation_release_gate").status());
    }
}
