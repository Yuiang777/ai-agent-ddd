package cn.apple.api;

import cn.apple.application.multimodal.ModelCapabilityService;
import cn.apple.application.multimodal.MultimodalService;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/multimodal")
public class MultimodalController {

    private final MultimodalService multimodalService;
    private final ModelCapabilityService modelCapabilityService;
    private final String modelClientId;

    @Autowired
    public MultimodalController(MultimodalService multimodalService, ModelCapabilityService modelCapabilityService,
                                @Value("${multimodal.model-client-id:${workflow.model-client-id:law-consultant}}") String modelClientId) {
        this.multimodalService = multimodalService;
        this.modelCapabilityService = modelCapabilityService;
        this.modelClientId = modelClientId == null || modelClientId.isBlank() ? "law-consultant" : modelClientId.trim();
    }

    public MultimodalController(MultimodalService multimodalService, ModelCapabilityService modelCapabilityService) {
        this(multimodalService, modelCapabilityService, "law-consultant");
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MultimodalService.MultimodalTask> submit(
            @RequestParam(value = "clientId", required = false) String ignoredClientId,
            @RequestParam("taskType") String taskType,
            @RequestParam(value = "prompt", required = false) String prompt,
            @RequestParam(value = "file", required = false) MultipartFile file
    ) {
        try {
            return ApiResponse.ok(multimodalService.submit(modelClientId, taskType, prompt, file));
        } catch (Exception e) {
            return ApiResponse.error(buildErrorMessage(e));
        }
    }

    @GetMapping("/capabilities")
    public ApiResponse<?> capabilities() {
        try {
            multimodalService.requireAccess();
            return ApiResponse.ok(modelCapabilityService.capabilities());
        } catch (Exception error) {
            return ApiResponse.error(buildErrorMessage(error));
        }
    }

    @GetMapping("/tasks")
    public ApiResponse<?> tasks() {
        try {
            return ApiResponse.ok(multimodalService.recentTasks());
        } catch (Exception error) {
            return ApiResponse.error(buildErrorMessage(error));
        }
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<?> task(@PathVariable String taskId) {
        try {
            MultimodalService.MultimodalTask task = multimodalService.getTask(taskId);
            if (task == null) return ApiResponse.error("任务不存在");
            return ApiResponse.ok(task);
        } catch (Exception error) {
            return ApiResponse.error(buildErrorMessage(error));
        }
    }

    @GetMapping("/assets/{fileName:.+}")
    public ResponseEntity<?> asset(@PathVariable String fileName) {
        try {
            Path path = multimodalService.resolveAsset(fileName);
            if (path == null || !Files.exists(path)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok().contentType(multimodalService.mediaType(path)).body(new UrlResource(path.toUri()));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body("MULTIMODAL_ACCESS_DENIED");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(buildErrorMessage(e));
        }
    }

    private String buildErrorMessage(Exception e) {
        return e instanceof SecurityException ? "MULTIMODAL_ACCESS_DENIED" : "MULTIMODAL_REQUEST_FAILED";
    }
}
