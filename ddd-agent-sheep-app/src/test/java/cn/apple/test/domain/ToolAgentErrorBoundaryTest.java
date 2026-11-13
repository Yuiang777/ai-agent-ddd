package cn.apple.test.domain;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.apple.api.ToolAgentController;
import cn.apple.application.agent.AiClientRuntimeService;
import cn.apple.application.agent.ToolAgentService;
import cn.apple.application.governance.GovernanceAuditService;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ToolAgentErrorBoundaryTest {
    private ToolAgentService service(Exception failure, AtomicInteger calls) {
        AiClientRuntimeService runtime = new AiClientRuntimeService(null, null, true, 3, 3, 30000) {
            @Override
            public String invokeToolText(String clientId, String operation, String prompt) throws Exception {
                calls.incrementAndGet();
                throw failure;
            }
        };
        return new ToolAgentService(runtime, new GovernanceAuditService());
    }

    @Test
    public void upstreamDetailsNeverReachResponseLogsOrWrapperCause() {
        String sensitive = "fake-tool-credential";
        AtomicInteger calls = new AtomicInteger();
        ToolAgentService service = service(new RuntimeException("503 Authorization: " + sensitive), calls);
        Logger logger = (Logger) LoggerFactory.getLogger(ToolAgentService.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.addAppender(capture);
        try {
            var response = new ToolAgentController(service).toolCall(new ToolAgentService.ToolAgentRequest("test", "hello"));
            assertFalse(response.success());
            assertEquals("MODEL_INVOCATION_FAILED", response.message());
            assertEquals(1, calls.get());
            try {
                service.execute(new ToolAgentService.ToolAgentRequest("test", "hello"));
                fail("Expected sanitized failure");
            } catch (IllegalStateException error) {
                assertEquals("MODEL_INVOCATION_FAILED", error.getMessage());
                assertNull(error.getCause());
            }
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
    public void localAvailabilityCodesRemainExactAndValidationStillWorks() {
        for (String code : new String[]{"MODEL_RUNTIME_DISABLED", "MODEL_CONCURRENCY_LIMIT", "MODEL_CIRCUIT_OPEN"}) {
            var response = new ToolAgentController(service(new IllegalStateException(code), new AtomicInteger()))
                    .toolCall(new ToolAgentService.ToolAgentRequest("test", "hello"));
            assertEquals(code, response.message());
        }
        AtomicInteger calls = new AtomicInteger();
        var controller = new ToolAgentController(service(new RuntimeException(), calls));
        assertEquals("message 不能为空", controller.toolCall(new ToolAgentService.ToolAgentRequest("test", " ")).message());
        assertEquals(0, calls.get());
        assertEquals("MODEL_INVOCATION_FAILED", new ToolAgentController(service(
                new IllegalStateException("MODEL_CIRCUIT_OPEN credential"), calls))
                .toolCall(new ToolAgentService.ToolAgentRequest("test", "hello")).message());
    }
}
