package cn.apple.api;

import cn.apple.application.workflow.LegalOrchestratorService;
import cn.apple.application.workflow.LitigationUploadAuthorizationService;
import cn.apple.config.UploadFileValidator;
import cn.apple.config.TenantContext;
import cn.apple.domain.workflow.model.WorkflowAttachment;
import cn.apple.domain.workflow.model.WorkflowDefinition;
import cn.apple.domain.workflow.model.WorkflowEvent;
import cn.apple.domain.workflow.model.WorkflowRunResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private static final int MAX_MESSAGE_LENGTH = 20000;

    private final LegalOrchestratorService legalOrchestratorService;
    private final LitigationUploadAuthorizationService litigationUploadAuthorizationService;
    private final UploadFileValidator uploadFileValidator;
    private final ThreadPoolExecutor threadPoolExecutor;
    private final Path storageRoot;
    private final String modelClientId;

    public WorkflowController(LegalOrchestratorService legalOrchestratorService,
                              LitigationUploadAuthorizationService litigationUploadAuthorizationService,
                              UploadFileValidator uploadFileValidator,
                              ThreadPoolExecutor threadPoolExecutor,
                              @Value("${workflow.storage-root:./data/workflows}") String storageRoot,
                              @Value("${workflow.model-client-id:law-consultant}") String modelClientId) {
        this.legalOrchestratorService = legalOrchestratorService;
        this.litigationUploadAuthorizationService = litigationUploadAuthorizationService;
        this.uploadFileValidator = uploadFileValidator;
        this.threadPoolExecutor = threadPoolExecutor;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.modelClientId = required(modelClientId, "workflow.model-client-id");
    }

    @org.springframework.web.bind.annotation.GetMapping("/templates/default")
    public ApiResponse<WorkflowDefinition> defaultTemplate() {
        return ApiResponse.ok(legalOrchestratorService.defaultWorkflow());
    }

    @PostMapping("/run")
    public ApiResponse<WorkflowRunResult> run(@RequestBody RunWorkflowRequest request) {
        try {
            TenantContext.Principal principal = TenantContext.current();
            WorkflowRunResult result = legalOrchestratorService.run(
                    modelClientId,
                    requiredMessage(request.message()),
                    legalOrchestratorService.normalizeInputVariables(request.variables()),
                    List.of(),
                    ignored -> {
                    },
                    principal == null ? null : principal.tenantId(),
                    principal == null ? null : principal.actorId()
            );
            return ApiResponse.ok(result);
        } catch (Exception e) {
            log.warn("workflow run failed: {}", e.getClass().getSimpleName());
            return ApiResponse.error("工作流执行失败，请查看服务端日志并人工复核");
        }
    }

    @PostMapping("/uploads/authorize")
    public ApiResponse<LitigationUploadAuthorizationService.UploadAuthorization> authorizeUpload(@RequestBody RunWorkflowRequest request) {
        try {
            String message = requiredMessage(request.message());
            Map<String, Object> variables = legalOrchestratorService.normalizeInputVariables(request.variables());
            return ApiResponse.ok(litigationUploadAuthorizationService.issue(modelClientId, message, variables));
        } catch (Exception e) {
            log.warn("workflow upload authorization failed: {}", e.getClass().getSimpleName());
            return ApiResponse.error("案件材料上传授权失败，请核对受理信息或联系管理员");
        }
    }

    @PostMapping("/run/stream")
    public SseEmitter runStream(@RequestBody RunWorkflowRequest request) {
        SseEmitter emitter = createEmitter();
        try {
            String message = requiredMessage(request == null ? null : request.message());
            Map<String, Object> variables = legalOrchestratorService.normalizeInputVariables(
                    request == null ? null : request.variables());
            TenantContext.Principal principal = TenantContext.current();
            threadPoolExecutor.execute(() -> {
                AtomicReference<WorkflowEvent> lastEvent = new AtomicReference<>();
                try {
                    legalOrchestratorService.run(modelClientId, message, variables, List.of(), event -> {
                        lastEvent.set(event);
                        try {
                            sendEvent(emitter, event);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }, principal == null ? null : principal.tenantId(),
                            principal == null ? null : principal.actorId());
                    emitter.complete();
                } catch (Exception e) {
                    sendFailureQuietly(emitter, lastEvent.get(), e, "unknown");
                    emitter.complete();
                }
            });
        } catch (RuntimeException e) {
            sendFailureQuietly(emitter, null, e, "queue");
            emitter.complete();
        }

        return emitter;
    }

    @PostMapping(value = "/run/stream/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SseEmitter runStreamUpload(@RequestParam("message") String message,
                                      @RequestParam(value = "representedPartyName", required = false) String representedPartyName,
                                      @RequestParam(value = "partyRole", required = false) String partyRole,
                                      @RequestParam(value = "proceedingType", required = false) String proceedingType,
                                      @RequestParam(value = "proceedingStage", required = false) String proceedingStage,
                                      @RequestParam(value = "courtOrTribunal", required = false) String courtOrTribunal,
                                      @RequestParam(value = "opponentNames", required = false) String opponentNames,
                                      @RequestParam(value = "conflictCheckCompleted", required = false) Boolean conflictCheckCompleted,
                                      @RequestParam(value = "engagementAuthorized", required = false) Boolean engagementAuthorized,
                                      @RequestParam(value = "caseNumber", required = false) String caseNumber,
                                      @RequestParam(value = "nextDeadline", required = false) String nextDeadline,
                                      @RequestParam(value = "conflictCheckReference", required = false) String conflictCheckReference,
                                      @RequestParam(value = "engagementReference", required = false) String engagementReference,
                                      @RequestParam(value = "deadlineSource", required = false) String deadlineSource,
                                      @RequestParam(value = "matterReference", required = false) String matterReference,
                                      @RequestParam("file") MultipartFile file,
                                      @RequestHeader(LitigationUploadAuthorizationService.HEADER) String uploadAuthorization) {
        SseEmitter emitter = createEmitter();
        String checkedMessage;
        Map<String, Object> normalizedVariables;
        try {
            checkedMessage = requiredMessage(message);
            Map<String, Object> variables = new java.util.LinkedHashMap<>();
            putIfPresent(variables, "representedPartyName", representedPartyName);
            putIfPresent(variables, "partyRole", partyRole);
            putIfPresent(variables, "proceedingType", proceedingType);
            putIfPresent(variables, "proceedingStage", proceedingStage);
            putIfPresent(variables, "courtOrTribunal", courtOrTribunal);
            putIfPresent(variables, "opponentNames", opponentNames);
            putIfPresent(variables, "caseNumber", caseNumber);
            putIfPresent(variables, "nextDeadline", nextDeadline);
            putIfPresent(variables, "conflictCheckReference", conflictCheckReference);
            putIfPresent(variables, "engagementReference", engagementReference);
            putIfPresent(variables, "deadlineSource", deadlineSource);
            putIfPresent(variables, "matterReference", matterReference);
            if (conflictCheckCompleted != null) variables.put("conflictCheckCompleted", conflictCheckCompleted);
            if (engagementAuthorized != null) variables.put("engagementAuthorized", engagementAuthorized);
            normalizedVariables = legalOrchestratorService.normalizeInputVariables(variables);
        } catch (Exception e) {
            sendFailureQuietly(emitter, null, e, "validation");
            emitter.complete();
            return emitter;
        }
        WorkflowAttachment stagedAttachment;
        try {
            litigationUploadAuthorizationService.consume(
                    uploadAuthorization, modelClientId, checkedMessage, normalizedVariables);
            stagedAttachment = saveAsset(file);
        } catch (Exception e) {
            log.warn("workflow upload staging failed: {}", e.getClass().getSimpleName());
            sendFailureQuietly(emitter, null, e, "upload");
            emitter.complete();
            return emitter;
        }

        try {
            TenantContext.Principal principal = TenantContext.current();
            threadPoolExecutor.execute(() -> {
                WorkflowAttachment attachment = stagedAttachment;
                AtomicReference<WorkflowEvent> lastEvent = new AtomicReference<>();
                try {
                    legalOrchestratorService.run(modelClientId, checkedMessage, normalizedVariables, List.of(attachment), event -> {
                        lastEvent.set(event);
                        try {
                            sendEvent(emitter, event);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }, principal == null ? null : principal.tenantId(),
                            principal == null ? null : principal.actorId());
                    emitter.complete();
                } catch (Exception e) {
                    sendFailureQuietly(emitter, lastEvent.get(), e, "unknown");
                    emitter.complete();
                } finally {
                    deleteAssetQuietly(attachment);
                }
            });
        } catch (RuntimeException e) {
            deleteAssetQuietly(stagedAttachment);
            try {
                sendEvent(emitter, new WorkflowEvent(
                        cn.apple.domain.workflow.model.WorkflowEventType.WORKFLOW_FAILED,
                        "unknown", "unknown", null, null,
                        "工作流执行队列暂不可用",
                        Map.of("error", "工作流执行队列暂不可用", "failedNode", "queue"),
                        LocalDateTime.now()
                ));
            } catch (Exception ignore) {
            }
            emitter.complete();
        }

        return emitter;
    }

    private WorkflowAttachment saveAsset(MultipartFile file) throws Exception {
        Files.createDirectories(storageRoot);
        String original = file.getOriginalFilename() == null ? "asset" : Path.of(file.getOriginalFilename()).getFileName().toString();
        String suffix = uploadFileValidator.validateAndExtension(file);
        Path target = Files.createTempFile(storageRoot, "workflow-", suffix).normalize();
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            String contentType = file.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.getContentType();
            return new WorkflowAttachment(target.getFileName().toString(), original, contentType, null, target.toString());
        } catch (Exception e) {
            Files.deleteIfExists(target);
            throw e;
        }
    }

    private void deleteAssetQuietly(WorkflowAttachment attachment) {
        if (attachment == null || attachment.localPath() == null || attachment.localPath().isBlank()) {
            return;
        }
        try {
            Path path = Path.of(attachment.localPath()).normalize();
            if (path.startsWith(storageRoot)) {
                Files.deleteIfExists(path);
            }
        } catch (Exception e) {
            log.warn("delete workflow temp asset failed: {}", e.getMessage());
        }
    }

    private void sendFailureQuietly(SseEmitter emitter, WorkflowEvent previous, Exception error, String fallbackNode) {
        try {
            if (previous != null && previous.type() == cn.apple.domain.workflow.model.WorkflowEventType.WORKFLOW_FAILED) {
                return;
            }
            log.warn("workflow execution failed at {}: {}", fallbackNode, error.getClass().getSimpleName());
            sendEvent(emitter, new WorkflowEvent(
                    cn.apple.domain.workflow.model.WorkflowEventType.WORKFLOW_FAILED,
                    previous == null ? "unknown" : previous.workflowId(),
                    previous == null ? "unknown" : previous.runId(),
                    previous == null ? null : previous.nodeId(),
                    previous == null ? null : previous.nodeType(),
                    "工作流执行失败，请查看服务端日志并人工复核",
                    Map.of(
                            "error", "工作流执行失败，请查看服务端日志并人工复核",
                            "failedNode", previous == null || previous.nodeId() == null ? fallbackNode : previous.nodeId()
                    ),
                    LocalDateTime.now()
            ));
        } catch (Exception ignore) {
        }
    }

    private SseEmitter createEmitter() {
        SseEmitter emitter = new SseEmitter(300000L);
        emitter.onTimeout(emitter::complete);
        emitter.onError(throwable -> log.warn("workflow sse failed: {}", throwable.getMessage()));
        emitter.onCompletion(() -> log.debug("workflow sse completed"));
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, WorkflowEvent event) throws Exception {
        emitter.send(SseEmitter.event()
                .name(event.type().name())
                .id(event.runId() + ":" + (event.nodeId() == null ? "workflow" : event.nodeId()))
                .data(event));
    }

    private String required(String value, String name) {
        String checked = value == null ? "" : value.trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return checked;
    }

    private String requiredMessage(String value) {
        String checked = required(value, "message");
        if (checked.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message 过长");
        }
        return checked;
    }

    private void putIfPresent(Map<String, Object> variables, String key, String value) {
        if (value != null && !value.trim().isEmpty()) variables.put(key, value.trim());
    }

    public record RunWorkflowRequest(String message, Map<String, Object> variables) {
    }
}
