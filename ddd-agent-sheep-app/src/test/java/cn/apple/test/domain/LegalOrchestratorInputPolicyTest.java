package cn.apple.test.domain;

import cn.apple.application.workflow.LegalOrchestratorService;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class LegalOrchestratorInputPolicyTest {

    @Test
    public void shouldKeepBusinessIntakeFieldsAndDropClientControlledPolicyFields() {
        LegalOrchestratorService service = new LegalOrchestratorService(null, null, null);
        Map<String, Object> normalized = service.normalizeInputVariables(Map.ofEntries(
                Map.entry("matterReference", " MAT-001 "),
                Map.entry("partyRole", " PLAINTIFF "),
                Map.entry("conflictCheckCompleted", true),
                Map.entry("workflowBlocked", true),
                Map.entry("reviewRequired", false),
                Map.entry("tenantId", "tenant-b"),
                Map.entry("knowledgeTag", "private-space"),
                Map.entry("topK", 1000)
        ));

        assertEquals("MAT-001", normalized.get("matterReference"));
        assertEquals("PLAINTIFF", normalized.get("partyRole"));
        assertEquals(Boolean.TRUE, normalized.get("conflictCheckCompleted"));
        assertFalse(normalized.containsKey("workflowBlocked"));
        assertFalse(normalized.containsKey("reviewRequired"));
        assertFalse(normalized.containsKey("tenantId"));
        assertFalse(normalized.containsKey("knowledgeTag"));
        assertFalse(normalized.containsKey("topK"));
    }
}
