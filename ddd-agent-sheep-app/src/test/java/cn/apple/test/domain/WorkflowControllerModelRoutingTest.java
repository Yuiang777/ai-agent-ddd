package cn.apple.test.domain;

import cn.apple.api.ApiResponse;
import cn.apple.api.WorkflowController;
import cn.apple.application.workflow.LegalOrchestratorService;
import cn.apple.application.workflow.LitigationUploadAuthorizationService;
import cn.apple.domain.workflow.model.WorkflowAttachment;
import cn.apple.domain.workflow.model.WorkflowEvent;
import cn.apple.domain.workflow.model.WorkflowRunResult;
import cn.apple.domain.workflow.model.WorkflowStatus;
import cn.apple.config.TenantContext;
import cn.apple.domain.legal.matter.MatterActorRole;
import org.junit.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WorkflowControllerModelRoutingTest {

    @Test
    public void requestContractMustNotExposeModelClientId() {
        Set<String> components = Arrays.stream(WorkflowController.RunWorkflowRequest.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());

        assertEquals(Set.of("message", "variables"), components);
        assertFalse(components.contains("clientId"));
    }

    @Test
    public void controllerMustUseServerConfiguredModelRouteAndDropPolicyVariables() {
        CapturingOrchestrator orchestrator = new CapturingOrchestrator();
        WorkflowController controller = new WorkflowController(
                orchestrator, null, null, null, "./target/workflow-routing-test", "approved-legal-model");

        ApiResponse<WorkflowRunResult> response = controller.run(new WorkflowController.RunWorkflowRequest(
                "准备答辩", Map.of("clientId", "browser-controlled", "tenantId", "tenant-controlled",
                        "matterReference", "MAT-001")));

        assertTrue(response.success());
        assertEquals("approved-legal-model", orchestrator.clientId);
        assertEquals("MAT-001", orchestrator.variables.get("matterReference"));
        assertFalse(orchestrator.variables.containsKey("clientId"));
        assertFalse(orchestrator.variables.containsKey("tenantId"));
    }

    @Test
    public void controllerMustPropagateAuthenticatedScopeOutsideClientVariables() {
        CapturingOrchestrator orchestrator = new CapturingOrchestrator();
        WorkflowController controller = new WorkflowController(
                orchestrator, null, null, null, "./target/workflow-routing-test", "approved-legal-model");
        TenantContext.set(new TenantContext.Principal("tenant-secure", "lawyer-9", MatterActorRole.LAWYER));
        try {
            ApiResponse<WorkflowRunResult> response = controller.run(new WorkflowController.RunWorkflowRequest(
                    "准备答辩", Map.of("matterReference", "MAT-009")));

            assertTrue(response.success());
            assertEquals("tenant-secure", orchestrator.tenantId);
            assertEquals("lawyer-9", orchestrator.actorId);
            assertFalse(orchestrator.variables.containsKey("tenantId"));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    public void workflowFailuresDoNotExposeRawModelContent() {
        LegalOrchestratorService orchestrator = new LegalOrchestratorService(null, null, null) {
            @Override
            public WorkflowRunResult run(String clientId, String message, Map<String, Object> variables,
                                         List<WorkflowAttachment> attachments, Consumer<WorkflowEvent> eventSink,
                                         String tenantId, String actorId) {
                throw new IllegalStateException("未经核验的敏感模型输出");
            }
        };
        WorkflowController controller = new WorkflowController(
                orchestrator, null, null, null, "./target/workflow-routing-test", "approved-legal-model");
        ApiResponse<WorkflowRunResult> response = controller.run(
                new WorkflowController.RunWorkflowRequest("准备答辩", Map.of()));
        assertFalse(response.success());
        assertFalse(response.message().contains("未经核验的敏感模型输出"));
    }

    @Test
    public void uploadAuthorizationFailureDoesNotExposeRawException() {
        LitigationUploadAuthorizationService authorization = new LitigationUploadAuthorizationService() {
            @Override
            public UploadAuthorization issue(String clientId, String message, Map<String, Object> variables) {
                throw new IllegalStateException("sensitive backend details");
            }
        };
        WorkflowController controller = new WorkflowController(
                new CapturingOrchestrator(), authorization, null, null,
                "./target/workflow-routing-test", "approved-legal-model");

        ApiResponse<LitigationUploadAuthorizationService.UploadAuthorization> response =
                controller.authorizeUpload(new WorkflowController.RunWorkflowRequest("request", Map.of()));

        assertFalse(response.success());
        assertFalse(response.message().contains("sensitive backend details"));
    }

    @Test
    public void uploadStreamFailureDoesNotExposeRawException() throws Exception {
        LitigationUploadAuthorizationService authorization = new LitigationUploadAuthorizationService() {
            @Override
            public void consume(String token, String clientId, String message, Map<String, Object> variables) {
                throw new IllegalStateException("sensitive upload details");
            }
        };
        WorkflowController controller = new WorkflowController(
                new CapturingOrchestrator(), authorization, null, null,
                "./target/workflow-routing-test", "approved-legal-model");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        MvcResult request = mvc.perform(multipart("/api/workflows/run/stream/upload")
                        .file(new MockMultipartFile("file", "evidence.txt", "text/plain", new byte[]{65}))
                        .param("message", "request")
                        .header(LitigationUploadAuthorizationService.HEADER, "token"))
                .andReturn();
        String response = mvc.perform(asyncDispatch(request)).andReturn().getResponse().getContentAsString();

        assertTrue(response.contains("WORKFLOW_FAILED"));
        assertFalse(response.contains("sensitive upload details"));
        assertFalse(response.contains("IllegalStateException"));
    }

    private static final class CapturingOrchestrator extends LegalOrchestratorService {
        private String clientId;
        private Map<String, Object> variables;
        private String tenantId;
        private String actorId;

        private CapturingOrchestrator() {
            super(null, null, null);
        }

        @Override
        public WorkflowRunResult run(String clientId,
                                     String message,
                                     Map<String, Object> variables,
                                     List<WorkflowAttachment> attachments,
                                     Consumer<WorkflowEvent> eventSink,
                                     String tenantId,
                                     String actorId) {
            this.clientId = clientId;
            this.variables = variables;
            this.tenantId = tenantId;
            this.actorId = actorId;
            LocalDateTime now = LocalDateTime.now();
            return new WorkflowRunResult("legal-workflow-v1", "run-1", WorkflowStatus.SUCCESS,
                    "ok", List.of(), List.of(), Map.of(), 1, now, now);
        }
    }
}
