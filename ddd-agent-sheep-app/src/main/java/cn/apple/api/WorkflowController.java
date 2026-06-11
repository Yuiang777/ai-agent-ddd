package cn.apple.api;

import cn.apple.application.workflow.LegalOrchestratorService;
import cn.apple.config.UploadFileValidator;
import cn.apple.domain.workflow.model.WorkflowAttachment;
import cn.apple.domain.workflow.model.WorkflowDefinition;
import cn.apple.domain.workflow.model.WorkflowEvent;
import cn.apple.domain.workflow.model.WorkflowRunResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final LegalOrchestratorService legalOrchestratorService;
    private final UploadFileValidator uploadFileValidator;
    private final ThreadPoolExecutor threadPoolExecutor;
    private final Path storageRoot;

    public WorkflowController(LegalOrchestratorService legalOrchestratorService,
                              UploadFileValidator uploadFileValidator,
                              ThreadPoolExecutor threadPoolExecutor,
                              @Value("${workflow.storage-root:./data/workflows}") String storageRoot) {
        this.legalOrchestratorService = legalOrchestratorService;
        this.uploadFileValidator = uploadFileValidator;
        this.threadPoolExecutor = threadPoolExecutor;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    @GetMapping("/templates/default")
    public ApiResponse<WorkflowDefinition> defaultTemplate() {
        return ApiResponse.ok(legalOrchestratorService.defaultWorkflow());
    }

    @PostMapping("/run")
    public ApiResponse<WorkflowRunResult> run(@RequestBody RunWorkflowRequest request) {
        try {
            WorkflowRunResult result = legalOrchestratorService.run(
                    required(request.clientId(), "clientId"),
                    required(request.message(), "message"),
                    request.variables() == null ? Map.of() : request.variables(),
                    List.of(),
                    ignored -> {
                    }
            );
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @PostMapping("/run/stream")
    public SseEmitter runStream(@RequestBody RunWorkflowRequest request) {
        SseEmitter emitter = createEmitter();
        String clientId = required(request.clientId(), "clientId");
        String message = required(request.message(), "message");
        Map<String, Object> variables = request.variables() == null ? Map.of() : request.variables();

        threadPoolExecutor.execute(() -> {
            try {
                legalOrchestratorService.run(clientId, message, variables, List.of(), event -> {
                    try {
                        sendEvent(emitter, event);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                try {
                    sendEvent(emitter, new WorkflowEvent(
                            cn.apple.domain.workflow.model.WorkflowEventType.WORKFLOW_FAILED,
                            "unknown",
                            "unknown",
                            null,
                            null,
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                            Map.of("error", e.getClass().getName()),
                            LocalDateTime.now()
                    ));
                } catch (Exception ignore) {
                }
                emitter.complete();
            }
        });

        return emitter;
    }

    @PostMapping(value = "/run/stream/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SseEmitter runStreamUpload(@RequestParam("clientId") String clientId,
                                      @RequestParam("message") String message,
                                      @RequestParam(value = "knowledgeTag", required = false) String knowledgeTag,
                                      @RequestParam(value = "topK", required = false) Integer topK,
                                      @RequestParam("file") MultipartFile file) {
        SseEmitter emitter = createEmitter();
        String checkedClientId = required(clientId, "clientId");
        String checkedMessage = required(message, "message");
        Map<String, Object> variables = new java.util.LinkedHashMap<>();
        if (knowledgeTag != null && !knowledgeTag.trim().isEmpty()) {
            variables.put("knowledgeTag", knowledgeTag.trim());
        }
        if (topK != null) {
            variables.put("topK", topK);
        }

        threadPoolExecutor.execute(() -> {
            WorkflowAttachment attachment = null;
            try {
                attachment = saveAsset(file);
                legalOrchestratorService.run(checkedClientId, checkedMessage, variables, List.of(attachment), event -> {
                    try {
                        sendEvent(emitter, event);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                try {
                    sendEvent(emitter, new WorkflowEvent(
                            cn.apple.domain.workflow.model.WorkflowEventType.WORKFLOW_FAILED,
                            "unknown",
                            "unknown",
                            null,
                            null,
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                            Map.of("error", e.getClass().getName()),
                            LocalDateTime.now()
                    ));
                } catch (Exception ignore) {
                }
                emitter.complete();
            } finally {
                deleteAssetQuietly(attachment);
            }
        });

        return emitter;
    }

    @GetMapping("/assets/{fileName:.+}")
    public ResponseEntity<?> asset(@org.springframework.web.bind.annotation.PathVariable String fileName) {
        try {
            Path path = resolveAsset(fileName);
            if (path == null || !Files.exists(path)) {
                return ResponseEntity.notFound().build();
            }
            MediaType mediaType = mediaType(path);
            return ResponseEntity.ok().contentType(mediaType).body(new UrlResource(path.toUri()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    private Path resolveAsset(String fileName) {
        Path path = storageRoot.resolve(Path.of(fileName).getFileName()).normalize();
        if (!path.startsWith(storageRoot)) {
            return null;
        }
        return path;
    }

    private MediaType mediaType(Path path) {
        try {
            String type = Files.probeContentType(path);
            return type == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(type);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private WorkflowAttachment saveAsset(MultipartFile file) throws Exception {
        Files.createDirectories(storageRoot);
        String original = file.getOriginalFilename() == null ? "asset" : Path.of(file.getOriginalFilename()).getFileName().toString();
        String suffix = uploadFileValidator.validateAndExtension(file);
        Path target = Files.createTempFile(storageRoot, "workflow-", suffix).normalize();
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        String contentType = file.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.getContentType();
        return new WorkflowAttachment(target.getFileName().toString(), original, contentType, null, target.toString());
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

    public record RunWorkflowRequest(String clientId, String message, Map<String, Object> variables) {
    }
}
