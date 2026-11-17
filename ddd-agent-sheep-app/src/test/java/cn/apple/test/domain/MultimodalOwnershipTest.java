package cn.apple.test.domain;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.apple.api.MultimodalController;
import cn.apple.application.agent.AiClientRuntimeService;
import cn.apple.application.governance.GovernanceAuditService;
import cn.apple.application.multimodal.MultimodalService;
import cn.apple.config.TenantContext;
import cn.apple.config.UploadFileValidator;
import cn.apple.config.UploadProperties;
import cn.apple.domain.legal.matter.MatterActorRole;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class MultimodalOwnershipTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final AtomicInteger calls = new AtomicInteger();

    @After public void clearIdentity() { TenantContext.clear(); }

    private void identity(String tenant, String actor) {
        TenantContext.set(new TenantContext.Principal(tenant, actor, MatterActorRole.LAWYER));
    }

    private AiClientRuntimeService runtime(boolean fail) {
        return new AiClientRuntimeService(null, null, true, 2, 3, 30000) {
            @Override public String invokeText(String clientId, String operation, String prompt) {
                calls.incrementAndGet();
                if (fail) throw new IllegalStateException("private-upstream-marker", new RuntimeException("private-cause-marker"));
                return "simulated output";
            }
        };
    }

    private MultimodalService service(boolean security, boolean fail) throws Exception {
        return new MultimodalService(runtime(fail), new GovernanceAuditService(),
                new UploadFileValidator(new UploadProperties()), null, null,
                temporary.newFolder().toString(), security);
    }

    @Test public void taskListAndDetailRequireExactTenantAndActor() throws Exception {
        var service = service(true, false);
        identity("tenant-a", "actor-a");
        var task = service.submit("model", "image_generation", "private prompt", null);
        assertEquals(task, service.getTask(task.taskId()));
        assertEquals(1, service.recentTasks().size());
        identity("tenant-b", "actor-a");
        assertNull(service.getTask(task.taskId()));
        assertTrue(service.recentTasks().isEmpty());
        identity("tenant-a", "actor-b");
        assertNull(service.getTask(task.taskId()));
        assertTrue(service.recentTasks().isEmpty());
        identity("tenant-a", "actor-a");
        assertEquals(task, service.getTask(task.taskId()));
        assertEquals(1, calls.get());
    }

    @Test public void missingAndNonStaffIdentitiesFailBeforeFileOrModelWork() throws Exception {
        Path storage = temporary.newFolder().toPath();
        var service = new MultimodalService(runtime(false), new GovernanceAuditService(), null,
                null, null, storage.toString(), true);
        var file = new MockMultipartFile("file", "private.txt", "text/plain", "private".getBytes());
        assertThrows(SecurityException.class, () -> service.submit("model", "image_generation", "prompt", file));
        assertThrows(SecurityException.class, service::recentTasks);
        assertThrows(SecurityException.class, () -> service.getTask("unknown"));
        assertThrows(SecurityException.class, () -> service.resolveAsset("private.txt"));
        for (MatterActorRole role : new MatterActorRole[]{MatterActorRole.CLIENT, MatterActorRole.SYSTEM, null}) {
            TenantContext.set(new TenantContext.Principal("tenant-a", "actor-a", role));
            assertThrows(SecurityException.class, () -> service.submit("model", "image_generation", "prompt", file));
        }
        assertEquals(0, calls.get());
        try (var files = Files.list(storage)) { assertEquals(0, files.count()); }
    }

    @Test public void offlineModeStillWorksWithoutEnterpriseIdentity() throws Exception {
        var service = service(false, false);
        var task = service.submit("model", "image_generation", "prompt", null);
        assertEquals(task, service.getTask(task.taskId()));
        assertEquals(1, service.recentTasks().size());
    }

    @Test public void onlyOwnerCanReadAnActiveTemporaryAssetAndCleanupStillRuns() throws Exception {
        Path storage = temporary.newFolder().toPath();
        var service = new MultimodalService(runtime(false), new GovernanceAuditService(),
                new UploadFileValidator(new UploadProperties()), null, null, storage.toString(), true) {
            @Override public String recognizeImage(String clientId, String prompt, Path asset) {
                String name = asset.getFileName().toString();
                assertEquals(asset, resolveAsset(name));
                identity("tenant-b", "actor-a");
                assertNull(resolveAsset(name));
                identity("tenant-a", "actor-b");
                assertNull(resolveAsset(name));
                identity("tenant-a", "actor-a");
                assertNull(resolveAsset("../" + name));
                assertEquals(asset, resolveAsset(name));
                return "simulated image result";
            }
        };
        identity("tenant-a", "actor-a");
        Files.writeString(storage.resolve("untracked.txt"), "must not be exposed");
        assertNull(service.resolveAsset("untracked.txt"));
        var task = service.submit("model", "image_recognition", "prompt",
                new MockMultipartFile("file", "input.txt", "text/plain", "fixture".getBytes()));
        assertEquals("success", task.status());
        String fileName = task.inputAssetUrl().substring(task.inputAssetUrl().lastIndexOf('/') + 1);
        assertFalse(Files.exists(storage.resolve(fileName)));
        assertNull(service.resolveAsset(fileName));
    }

    @Test public void upstreamErrorNeverAppearsInTaskResponseOrServiceLogs() throws Exception {
        var service = service(true, true);
        identity("tenant-a", "actor-a");
        Logger logger = (Logger) LoggerFactory.getLogger(MultimodalService.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start(); logger.addAppender(capture);
        try {
            var controller = new MultimodalController(service, null);
            var response = controller.submit(null, "image_generation", "prompt", null);
            assertTrue(response.success());
            assertEquals("failed", response.data().status());
            assertEquals("MULTIMODAL_EXECUTION_FAILED", response.data().errorMessage());
            assertFalse(service.recentTasks().toString().contains("private-upstream-marker"));
            assertFalse(capture.list.isEmpty());
            for (var event : capture.list) {
                assertFalse(event.getFormattedMessage().contains("private-upstream-marker"));
                assertNull(event.getThrowableProxy());
            }
        } finally { logger.detachAppender(capture); capture.stop(); }
    }

    @Test public void controllerRejectsMissingIdentityWithoutLeakingErrors() throws Exception {
        var controller = new MultimodalController(service(true, false), null);
        assertEquals("MULTIMODAL_ACCESS_DENIED", controller.tasks().message());
        assertEquals("MULTIMODAL_ACCESS_DENIED", controller.task("unknown").message());
        assertEquals("MULTIMODAL_ACCESS_DENIED", controller.capabilities().message());
        assertEquals(403, controller.asset("private.txt").getStatusCode().value());
        identity("tenant-a", "actor-a");
        assertEquals("MULTIMODAL_REQUEST_FAILED", controller.submit(null, " ", "prompt", null).message());
    }
}
