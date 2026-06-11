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
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/multimodal")
public class MultimodalController {

    private final MultimodalService multimodalService;
    private final ModelCapabilityService modelCapabilityService;

    public MultimodalController(MultimodalService multimodalService, ModelCapabilityService modelCapabilityService) {
        this.multimodalService = multimodalService;
        this.modelCapabilityService = modelCapabilityService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MultimodalService.MultimodalTask> submit(
            @RequestParam("clientId") String clientId,
            @RequestParam("taskType") String taskType,
            @RequestParam(value = "prompt", required = false) String prompt,
            @RequestParam(value = "file", required = false) MultipartFile file
    ) {
        try {
            return ApiResponse.ok(multimodalService.submit(clientId, taskType, prompt, file));
        } catch (Exception e) {
            return ApiResponse.error(buildErrorMessage(e));
        }
    }

    @GetMapping("/capabilities")
    public ApiResponse<?> capabilities() {
        return ApiResponse.ok(modelCapabilityService.capabilities());
    }

    @GetMapping("/tasks")
    public ApiResponse<?> tasks() {
        return ApiResponse.ok(multimodalService.recentTasks());
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<?> task(@PathVariable String taskId) {
        MultimodalService.MultimodalTask task = multimodalService.getTask(taskId);
        if (task == null) {
            return ApiResponse.error("任务不存在");
        }
        return ApiResponse.ok(task);
    }

    @GetMapping("/assets/{fileName:.+}")
    public ResponseEntity<?> asset(@PathVariable String fileName) {
        try {
            Path path = multimodalService.resolveAsset(fileName);
            if (path == null || !Files.exists(path)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok().contentType(multimodalService.mediaType(path)).body(new UrlResource(path.toUri()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(buildErrorMessage(e));
        }
    }

    private String buildErrorMessage(Exception e) {
        if (e == null) {
            return "unknown";
        }
        String msg = e.getMessage() == null ? "" : e.getMessage();
        Throwable cause = e.getCause();
        String causeMsg = cause == null || cause.getMessage() == null ? "" : cause.getMessage();
        String causeType = cause == null ? "" : cause.getClass().getSimpleName();
        if (!causeType.isBlank() && !causeMsg.isBlank() && !causeMsg.equals(msg)) {
            return e.getClass().getSimpleName() + ": " + msg + " | cause=" + causeType + ": " + causeMsg;
        }
        if (!causeType.isBlank() && causeMsg.isBlank() && !causeType.equals(e.getClass().getSimpleName())) {
            return e.getClass().getSimpleName() + ": " + msg + " | cause=" + causeType;
        }
        return e.getClass().getSimpleName() + ": " + msg;
    }
}
