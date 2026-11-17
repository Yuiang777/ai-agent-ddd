package cn.apple.test.domain;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.apple.api.ChatController;
import cn.apple.application.agent.AiClientRuntimeService;
import cn.apple.application.governance.GovernanceAuditService;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

public class ChatControllerErrorBoundaryTest {
    private ChatController controller(Exception failure) {
        AiClientRuntimeService runtime = new AiClientRuntimeService(null, null, true, 3, 3, 30000) {
            @Override
            public String invokeText(String clientId, String operation, String prompt, String conversationId)
                    throws Exception {
                throw failure;
            }
        };
        return new ChatController(runtime, new GovernanceAuditService());
    }

    @Test
    public void upstreamDetailsNeverReachResponseOrControllerLogs() {
        String sensitive = "fake-credential-do-not-expose";
        Logger logger = (Logger) LoggerFactory.getLogger(ChatController.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.addAppender(capture);
        try {
            var response = controller(new RuntimeException("Authorization: Bearer " + sensitive))
                    .chat(new ChatController.ChatRequest("ignored", "hello"));
            assertFalse(response.success());
            assertNull(response.data());
            assertEquals("MODEL_INVOCATION_FAILED", response.message());
            assertFalse(capture.list.isEmpty());
            for (ILoggingEvent event : capture.list) {
                assertFalse(event.getFormattedMessage().contains(sensitive));
                assertNull(event.getThrowableProxy());
            }
        } finally {
            logger.detachAppender(capture);
            capture.stop();
        }
    }

    @Test
    public void knownLocalAvailabilityCodesRemainActionable() {
        for (String code : new String[]{"MODEL_RUNTIME_DISABLED", "MODEL_CONCURRENCY_LIMIT", "MODEL_CIRCUIT_OPEN"}) {
            var response = controller(new IllegalStateException(code))
                    .chat(new ChatController.ChatRequest("ignored", "hello"));
            assertFalse(response.success());
            assertEquals(code, response.message());
        }
    }

    @Test
    public void codePrefixesAndRemoteExceptionsDoNotBypassBoundary() {
        for (Exception failure : new Exception[]{
                new IllegalStateException("MODEL_CIRCUIT_OPEN fake-credential"),
                new RuntimeException("MODEL_RUNTIME_DISABLED"),
                new IllegalStateException()}) {
            assertEquals("MODEL_INVOCATION_FAILED", controller(failure)
                    .chat(new ChatController.ChatRequest("ignored", "hello")).message());
        }
    }
}
