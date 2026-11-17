package cn.apple.application.multimodal;

import cn.apple.application.agent.AiClientRuntimeService;
import cn.apple.application.governance.GovernanceAuditService;
import cn.apple.config.UploadFileValidator;
import cn.apple.config.TenantContext;
import cn.apple.domain.legal.matter.MatterActorRole;
import cn.apple.domain.agent.adapter.repository.IAgentRepository;
import cn.apple.domain.agent.model.valobj.AiAgentEnumVO;
import cn.apple.domain.agent.model.valobj.AiClientModelVO;
import cn.apple.domain.agent.model.valobj.AiClientToolMcpVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class MultimodalService {

    private static final int MAX_TASKS = 200;
    private static final int MAX_PROMPT_LENGTH = 20000;
    private static final String MINIMAX_IMAGE_MCP_ID = "minimax-image-understand";
    private static final AtomicBoolean MCP_TOOLS_LOGGED = new AtomicBoolean(false);

    private final AiClientRuntimeService aiClientRuntimeService;
    private final GovernanceAuditService governanceAuditService;
    private final UploadFileValidator uploadFileValidator;
    private final IAgentRepository agentRepository;
    private final ApplicationContext applicationContext;
    private final Path storageRoot;
    private final boolean securityContextEnabled;
    private final Map<String, OwnedTask> taskMap = new ConcurrentHashMap<>();
    private final ArrayDeque<String> taskOrder = new ArrayDeque<>();

    @Autowired
    public MultimodalService(AiClientRuntimeService aiClientRuntimeService,
                             GovernanceAuditService governanceAuditService,
                             UploadFileValidator uploadFileValidator,
                             IAgentRepository agentRepository,
                             ApplicationContext applicationContext,
                             @Value("${multimodal.storage-root:./data/multimodal}") String storageRoot,
                             @Value("${app.enterprise.security-context-enabled:false}") boolean securityContextEnabled) {
        this.aiClientRuntimeService = aiClientRuntimeService;
        this.governanceAuditService = governanceAuditService;
        this.uploadFileValidator = uploadFileValidator;
        this.agentRepository = agentRepository;
        this.applicationContext = applicationContext;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.securityContextEnabled = securityContextEnabled;
    }

    public MultimodalService(AiClientRuntimeService runtime, GovernanceAuditService audit,
                             UploadFileValidator validator, IAgentRepository repository,
                             ApplicationContext context, String storageRoot) {
        this(runtime, audit, validator, repository, context, storageRoot, false);
    }

    public void requireAccess() {
        currentOwner();
    }

    private TaskOwner currentOwner() {
        TenantContext.Principal principal = TenantContext.current();
        if (principal == null && !securityContextEnabled) {
            return new TaskOwner("offline-tenant", "offline-user");
        }
        if (principal == null) throw new SecurityException("MULTIMODAL_IDENTITY_REQUIRED");
        if (principal.role() != MatterActorRole.LAWYER && principal.role() != MatterActorRole.PARALEGAL) {
            throw new SecurityException("MULTIMODAL_STAFF_REQUIRED");
        }
        return new TaskOwner(principal.tenantId(), principal.actorId());
    }

    public MultimodalTask submit(String clientId, String taskType, String prompt, MultipartFile file) throws Exception {
        TaskOwner owner = currentOwner();
        long start = System.currentTimeMillis();
        String checkedClientId = required(clientId, "clientId");
        String checkedTaskType = required(taskType, "taskType");
        String checkedPrompt = prompt == null ? "" : prompt.trim();
        if (checkedPrompt.length() > MAX_PROMPT_LENGTH) {
            throw new IllegalArgumentException("prompt 过长");
        }
        if (!governanceAuditService.allowRequest(checkedClientId)) {
            governanceAuditService.recordChat(checkedClientId, checkedPrompt, false, System.currentTimeMillis() - start, "multimodal_rate_limited");
            throw new IllegalStateException("请求过于频繁，请稍后再试");
        }

        Path assetPath = null;
        String assetUrl = null;
        if (file != null && !file.isEmpty()) {
            assetPath = saveAsset(file);
            assetUrl = "/api/multimodal/assets/" + assetPath.getFileName();
        }

        String taskId = UUID.randomUUID().toString().replace("-", "");
        MultimodalTask task = new MultimodalTask(taskId, checkedClientId, checkedTaskType, checkedPrompt, assetUrl,
                "running", null, null, null, 0L, LocalDateTime.now(), LocalDateTime.now());
        putTask(task, owner);

        try {
            String content = switch (checkedTaskType) {
                case "image_recognition" -> recognizeImage(checkedClientId, checkedPrompt, assetPath);
                case "image_generation" -> generateImagePrompt(checkedClientId, checkedPrompt);
                case "video_recognition" -> recognizeVideoPlan(checkedClientId, checkedPrompt, assetPath);
                case "video_generation" -> generateVideoPlan(checkedClientId, checkedPrompt);
                default -> throw new IllegalArgumentException("不支持的任务类型：" + checkedTaskType);
            };
            long costMs = System.currentTimeMillis() - start;
            task = task.success(content, costMs);
            putTask(task, owner);
            governanceAuditService.recordChat(checkedClientId, checkedPrompt, true, costMs, "multimodal_" + checkedTaskType + "_ok");
            return task;
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - start;
            task = task.failed("MULTIMODAL_EXECUTION_FAILED", costMs);
            putTask(task, owner);
            governanceAuditService.recordChat(checkedClientId, checkedPrompt, false, costMs, "multimodal_" + checkedTaskType + "_failed");
            log.error("multimodal task failed, taskId={}, errorType={}", taskId, e.getClass().getSimpleName());
            return task;
        } finally {
            deleteAssetQuietly(assetPath);
        }
    }

    public List<MultimodalTask> recentTasks() {
        TaskOwner owner = currentOwner();
        synchronized (taskOrder) {
            return taskOrder.stream().map(taskMap::get)
                    .filter(entry -> entry != null && entry.owner().equals(owner))
                    .map(OwnedTask::task)
                    .sorted(Comparator.comparing(MultimodalTask::createTime).reversed())
                    .toList();
        }
    }

    public MultimodalTask getTask(String taskId) {
        TaskOwner owner = currentOwner();
        OwnedTask entry = taskMap.get(taskId);
        return entry != null && entry.owner().equals(owner) ? entry.task() : null;
    }

    public Path resolveAsset(String fileName) {
        TaskOwner owner = currentOwner();
        if (fileName == null || !fileName.matches("[A-Za-z0-9._-]+")) return null;
        String assetUrl = "/api/multimodal/assets/" + fileName;
        boolean owned = taskMap.values().stream().anyMatch(entry -> entry.owner().equals(owner)
                && (assetUrl.equals(entry.task().inputAssetUrl()) || assetUrl.equals(entry.task().outputAssetUrl())));
        if (!owned) return null;
        Path path = storageRoot.resolve(fileName).normalize();
        return path.startsWith(storageRoot) && Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                ? path : null;
    }

    public MediaType mediaType(Path path) {
        try {
            String type = Files.probeContentType(path);
            return type == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(type);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    public String recognizeImage(String clientId, String prompt, Path assetPath) throws Exception {
        if (assetPath == null) {
            throw new IllegalArgumentException("图片识别需要上传图片");
        }
        McpSyncClient mcpSyncClient = imageUnderstandingMcpClient(clientId);
        String userPrompt = prompt == null || prompt.isBlank()
                ? defaultImageRecognitionPrompt()
                : prompt;
        String mcpPrompt = latin1Compatible(userPrompt)
                ? userPrompt + " Respond in Simplified Chinese."
                : defaultImageRecognitionPrompt();
        String localPath = assetPath.toAbsolutePath().normalize().toString();
        String dataUri = imageDataUri(assetPath);
        McpSchema.CallToolResult result = callUnderstandImageWithFallback(mcpSyncClient, mcpPrompt, localPath, dataUri);
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException(mcpText(result));
        }
        return mcpText(result);
    }

    private String generateImagePrompt(String clientId, String prompt) throws Exception {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("图片生成需要输入提示词");
        }
        String instruction = "你是图片生成 Agent。当前系统还没有接入真实图片生成模型时，请先把用户需求改写为可直接发给图片生成模型的高质量提示词，包含主体、风格、镜头、构图、光线、尺寸建议和负面提示词。用户需求：" + prompt;
        return aiClientRuntimeService.invokeText(clientId, "image-prompt", instruction);
    }

    private String recognizeVideoPlan(String clientId, String prompt, Path assetPath) throws Exception {
        if (assetPath == null) {
            throw new IllegalArgumentException("视频识别需要上传视频");
        }
        String instruction = "你是视频理解 Agent。当前收到视频文件：" + assetPath.getFileName()
                + "。请基于用户需求输出视频理解方案、建议抽帧点、需要识别的对象/OCR/场景和最终摘要结构。用户需求：" + (prompt == null ? "" : prompt);
        return aiClientRuntimeService.invokeText(clientId, "video-understanding", instruction);
    }

    private String generateVideoPlan(String clientId, String prompt) throws Exception {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("视频生成需要输入提示词");
        }
        String instruction = "你是视频生成 Agent。当前系统还没有接入真实视频生成模型时，请先输出可执行的视频生成脚本，包括分镜、画面、动作、时长、镜头、音效、字幕和负面提示词。用户需求：" + prompt;
        return aiClientRuntimeService.invokeText(clientId, "video-prompt", instruction);
    }

    private McpSyncClient imageUnderstandingMcpClient(String clientId) throws Exception {
        List<AiClientModelVO> models = agentRepository.AiClientModelVOByClientIds(List.of(clientId));
        String mcpId = models == null ? null : models.stream()
                .filter(model -> model.getToolMcpIdList() != null)
                .flatMap(model -> model.getToolMcpIdList().stream())
                .filter(MINIMAX_IMAGE_MCP_ID::equals)
                .findFirst()
                .orElse(null);
        if (mcpId == null) {
            List<AiClientToolMcpVO> mcps = agentRepository.AiClientToolMcpVOByClientIds(List.of(clientId));
            mcpId = mcps == null ? null : mcps.stream()
                    .map(AiClientToolMcpVO::getMcpId)
                    .filter(MINIMAX_IMAGE_MCP_ID::equals)
                    .findFirst()
                    .orElse(null);
        }
        if (mcpId == null) {
            throw new IllegalStateException("请在 ai_client_config 中给当前模型绑定 MiniMax 图片理解 MCP：" + MINIMAX_IMAGE_MCP_ID);
        }
        String beanName = AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(mcpId);
        if (!applicationContext.containsBean(beanName)) {
            AiClientToolMcpVO mcp = agentRepository.AiClientToolMcpVOByClientIds(List.of(clientId)).stream()
                    .filter(item -> MINIMAX_IMAGE_MCP_ID.equals(item.getMcpId()))
                    .findFirst()
                    .orElse(null);

            String detail = "";
            if (mcp != null) {
                detail = "mcpId=" + mcp.getMcpId()
                        + ", transportType=" + value(mcp.getTransportType());
                if ("stdio".equalsIgnoreCase(value(mcp.getTransportType())) && mcp.getTransportConfigStdio() != null && mcp.getTransportConfigStdio().getStdio() != null) {
                    AiClientToolMcpVO.TransportConfigStdio.Stdio stdio = mcp.getTransportConfigStdio().getStdio().get(value(mcp.getMcpName()));
                    if (stdio != null) {
                        detail = detail
                                + ", command=" + value(stdio.getCommand())
                                + ", args=" + (stdio.getArgs() == null ? List.of() : stdio.getArgs())
                                + ", envKeys=" + (stdio.getEnv() == null ? List.of() : stdio.getEnv().keySet());
                    }
                }
            }

            throw new IllegalStateException("""
                    MiniMax 图片理解 MCP 未装配成功（Bean 不存在）。
                    这通常表示 MCP 在装配阶段初始化失败，被系统跳过（不会影响普通聊天/RAG，只影响图片识别能力）。
                    请检查：
                    1) 你的网络/代理是否可访问 pypi.org（uvx 需要下载 minimax-coding-plan-mcp 包）。
                    2) ai_client_tool_mcp.transport_config 中的 uvx 路径、args 是否正确；如在公司网络可在 env 里配置 HTTPS_PROXY/HTTP_PROXY。
                    3) 或改用 SSE 方式部署 MCP（避免本机安装依赖），或临时禁用该 MCP（status=0）。
                    %s
                    """.formatted(detail.isBlank() ? "" : "当前配置：" + detail));
        }
        return applicationContext.getBean(beanName, McpSyncClient.class);
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }

    private String mcpText(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return "";
        }
        return result.content().stream()
                .map(content -> content instanceof McpSchema.TextContent textContent ? textContent.text() : content.toString())
                .filter(text -> text != null && !text.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String defaultImageRecognitionPrompt() {
        return "Please analyze this image and respond in Simplified Chinese. Include visible text, main subjects, key facts, possible risks, and legal-analysis points.";
    }

    private boolean latin1Compatible(String value) {
        return value.chars().allMatch(ch -> ch <= 255);
    }

    private McpSchema.CallToolResult callUnderstandImageWithFallback(McpSyncClient client, String prompt, String localPath, String dataUri) throws Exception {
        tryLogMcpTools(client);
        List<Map<String, Object>> attempts = List.of(
                Map.of("prompt", prompt, "image_url", dataUri),
                Map.of("prompt", prompt, "image_url", localPath),
                Map.of("prompt", prompt, "image_source", dataUri),
                Map.of("prompt", prompt, "image_source", localPath)
        );

        String lastError = null;
        for (Map<String, Object> args : attempts) {
            McpSchema.CallToolResult result;
            try {
                result = client.callTool(new McpSchema.CallToolRequest("understand_image", args));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                lastError = "exception=" + e.getClass().getSimpleName() + ", args=" + attemptSummary(args, localPath, dataUri) + ", message=" + msg;
                continue;
            }
            if (!Boolean.TRUE.equals(result.isError())) {
                return result;
            }
            String errText = mcpText(result);
            lastError = "args=" + attemptSummary(args, localPath, dataUri) + ", error=" + (errText == null ? "unknown" : errText);
            if (isArgumentValidationError(lastError)) {
                continue;
            }
            return result;
        }
        throw new IllegalStateException("MiniMax MCP understand_image 调用失败，已尝试多种参数形式仍失败。lastError=" + lastError);
    }

    private boolean isArgumentValidationError(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase();
        return t.contains("validation error")
                || t.contains("field required")
                || t.contains("type=missing")
                || t.contains("for understand_imagearguments")
                || t.contains("error executing tool understand_image");
    }

    private String attemptSummary(Map<String, Object> args, String localPath, String dataUri) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        String imageKey = args.containsKey("image_url") ? "image_url" : (args.containsKey("image_source") ? "image_source" : "image");
        Object imageValObj = args.get(imageKey);
        String imageVal = imageValObj == null ? "" : String.valueOf(imageValObj);
        String source;
        if (imageVal.equals(dataUri)) {
            source = "dataUri(len=" + dataUri.length() + ")";
        } else if (imageVal.equals(localPath)) {
            source = "localPath";
        } else if (imageVal.startsWith("data:")) {
            source = "dataUri(len=" + imageVal.length() + ")";
        } else {
            source = "value(len=" + imageVal.length() + ")";
        }
        return "{promptLen=" + (args.get("prompt") == null ? 0 : String.valueOf(args.get("prompt")).length()) + ", " + imageKey + "=" + source + "}";
    }

    private void tryLogMcpTools(McpSyncClient client) {
        if (client == null || MCP_TOOLS_LOGGED.get()) {
            return;
        }
        try {
            Object tools = client.getClass().getMethod("listTools").invoke(client);
            MCP_TOOLS_LOGGED.set(true);
            log.info("mcp tools: {}", tools);
        } catch (Exception ignored) {
        }
    }

    private String imageDataUri(Path assetPath) throws Exception {
        String mimeType = Files.probeContentType(assetPath);
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = "image/jpeg";
        }
        byte[] bytes = Files.readAllBytes(assetPath);
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private Path saveAsset(MultipartFile file) throws Exception {
        String suffix = uploadFileValidator.validateAndExtension(file);
        Files.createDirectories(storageRoot);
        Path target = Files.createTempFile(storageRoot, "multimodal-", suffix).normalize();
        try (java.io.InputStream input = file.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (Exception e) {
            Files.deleteIfExists(target);
            throw e;
        }
    }

    private void deleteAssetQuietly(Path assetPath) {
        if (assetPath == null) {
            return;
        }
        try {
            Path path = assetPath.toAbsolutePath().normalize();
            if (path.startsWith(storageRoot)) {
                Files.deleteIfExists(path);
            }
        } catch (Exception e) {
            log.warn("delete multimodal temp asset failed, errorType={}", e.getClass().getSimpleName());
        }
    }

    private void putTask(MultimodalTask task, TaskOwner owner) {
        synchronized (taskOrder) {
            taskMap.put(task.taskId(), new OwnedTask(owner, task));
            taskOrder.remove(task.taskId());
            taskOrder.addFirst(task.taskId());
            while (taskOrder.size() > MAX_TASKS) {
                String removed = taskOrder.removeLast();
                taskMap.remove(removed);
            }
        }
    }

    private String required(String value, String name) {
        String checked = value == null ? "" : value.trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return checked;
    }

    private record TaskOwner(String tenantId, String actorId) { }

    private record OwnedTask(TaskOwner owner, MultimodalTask task) { }

    public record MultimodalTask(String taskId,
                                 String clientId,
                                 String taskType,
                                 String prompt,
                                 String inputAssetUrl,
                                 String status,
                                 String result,
                                 String errorMessage,
                                 String outputAssetUrl,
                                 long costMs,
                                 LocalDateTime createTime,
                                 LocalDateTime updateTime) {
        public MultimodalTask success(String result, long costMs) {
            return new MultimodalTask(taskId, clientId, taskType, prompt, inputAssetUrl, "success", result, null, outputAssetUrl, costMs, createTime, LocalDateTime.now());
        }

        public MultimodalTask failed(String errorMessage, long costMs) {
            return new MultimodalTask(taskId, clientId, taskType, prompt, inputAssetUrl, "failed", result, errorMessage, outputAssetUrl, costMs, createTime, LocalDateTime.now());
        }
    }
}
