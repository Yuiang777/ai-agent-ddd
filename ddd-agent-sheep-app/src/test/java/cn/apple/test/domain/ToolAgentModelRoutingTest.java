package cn.apple.test.domain;

import cn.apple.application.agent.AiClientRuntimeService;
import cn.apple.application.agent.ToolAgentService;
import cn.apple.application.governance.GovernanceAuditService;
import org.junit.Test;
import static org.junit.Assert.*;

public class ToolAgentModelRoutingTest {
    @Test
    public void browserCannotChooseModelOrMountedTools() {
        String[] captured = new String[1];
        AiClientRuntimeService runtime = new AiClientRuntimeService(null, null, true, 3, 3, 30000) {
            @Override
            public String invokeToolText(String clientId, String operation, String prompt) {
                captured[0] = clientId;
                return "stub result";
            }
        };
        ToolAgentService service = new ToolAgentService(runtime, new GovernanceAuditService(), " approved-tool-client ");
        for (String supplied : new String[]{"privileged-admin-client", null, " ", "x".repeat(200)}) {
            var response = service.execute(new ToolAgentService.ToolAgentRequest(supplied, "hello"));
            assertEquals("approved-tool-client", captured[0]);
            assertEquals("approved-tool-client", response.clientId());
            assertEquals("stub result", response.content());
        }
    }

    @Test
    public void blankServerRouteUsesDefaultAndInvalidMessagesNeverInvokeModel() {
        int[] calls = new int[1];
        AiClientRuntimeService runtime = new AiClientRuntimeService(null, null, true, 3, 3, 30000) {
            @Override
            public String invokeToolText(String clientId, String operation, String prompt) {
                calls[0]++;
                assertEquals("law-consultant", clientId);
                return "stub result";
            }
        };
        ToolAgentService service = new ToolAgentService(runtime, new GovernanceAuditService(), " ");
        assertEquals("law-consultant", service.execute(new ToolAgentService.ToolAgentRequest(null, "hello")).clientId());
        for (ToolAgentService.ToolAgentRequest request : new ToolAgentService.ToolAgentRequest[]{null,
                new ToolAgentService.ToolAgentRequest("admin", " "),
                new ToolAgentService.ToolAgentRequest("admin", "x".repeat(20001))}) {
            try {
                service.execute(request);
                fail("Invalid request must fail before a model call");
            } catch (IllegalArgumentException expected) {
                assertEquals(1, calls[0]);
            }
        }
    }
}
