package cn.apple.domain.agent.service.armory;

import cn.apple.domain.agent.model.entity.ArmoryCommandEntity;
import cn.apple.domain.agent.model.valobj.AiAgentEnumVO;
import cn.apple.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.apple.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.applesheep.wrench.design.framework.tree.StrategyHandler;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author _apple_sheep_
 * @description ModelNode
 * @date 2026/1/5
 * @Copyright github:Yuiang777
 */
@Slf4j
@Service
public class AiClientToolMcpNode extends AbstractArmorySupport{

    private final AiClientModeNode aiClientModelNode;

    public AiClientToolMcpNode(AiClientModeNode aiClientModelNode) {
        this.aiClientModelNode = aiClientModelNode;
    }

    @Override
    protected String doApply(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        List<AiClientToolMcpVO> aiClientToolMcpVOS = dynamicContext.getValue(dataName());

        if(aiClientToolMcpVOS == null || aiClientToolMcpVOS.isEmpty()){
            log.warn("没有需要被初始化的 ai client tool mcp");
            return router(armoryCommandEntity, dynamicContext);
        }

        Map<String, String> initErrors = new LinkedHashMap<>();
        for (AiClientToolMcpVO aiClientToolMcpVO : aiClientToolMcpVOS){
            try {
                McpSyncClient mcpSyncClient = createMcpSyncClient(aiClientToolMcpVO);
                registerBean(beanName(aiClientToolMcpVO.getMcpId()), McpSyncClient.class, mcpSyncClient);
            } catch (Exception e) {
                String mcpId = aiClientToolMcpVO == null ? "" : sanitize(aiClientToolMcpVO.getMcpId());
                String mcpName = aiClientToolMcpVO == null ? "" : sanitize(aiClientToolMcpVO.getMcpName());
                String key = (mcpName == null || mcpName.isBlank()) ? mcpId : mcpName;
                String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                initErrors.put(key == null ? "" : key, err);
                log.warn("MCP 初始化失败，已跳过（不影响 Agent 装配）：mcpId={}, mcpName={}, err={}", mcpId, mcpName, err);
            }
        }

        if (!initErrors.isEmpty()) {
            dynamicContext.setValue("mcp_init_errors", initErrors);
        }
        return router(armoryCommandEntity, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return aiClientModelNode;
    }

    @Override
    public String beanName(String beanId){
        return AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(beanId);
    }

    @Override
    public String dataName(){
        return AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getDataName();
    }

    private McpSyncClient createMcpSyncClient(AiClientToolMcpVO aiClientToolMcpVO){
        String transportType = required(sanitize(aiClientToolMcpVO.getTransportType()), "transportType");
        int requestTimeout = aiClientToolMcpVO.getRequestTimeout() == null || aiClientToolMcpVO.getRequestTimeout() <= 0
                ? 60
                : aiClientToolMcpVO.getRequestTimeout();
        switch (transportType) {
            case "stdio" -> {
                AiClientToolMcpVO.TransportConfigStdio transportConfigStdio = aiClientToolMcpVO.getTransportConfigStdio();
                if (transportConfigStdio == null || transportConfigStdio.getStdio() == null || transportConfigStdio.getStdio().isEmpty()) {
                    throw new IllegalArgumentException("stdio MCP 配置不能为空");
                }
                Map<String, AiClientToolMcpVO.TransportConfigStdio.Stdio> stdio = transportConfigStdio.getStdio();
                AiClientToolMcpVO.TransportConfigStdio.Stdio stdio1 = stdio.get(required(sanitize(aiClientToolMcpVO.getMcpName()), "mcpName"));
                if (stdio1 == null) {
                    throw new IllegalArgumentException("stdio MCP 配置未包含 mcpName=" + aiClientToolMcpVO.getMcpName());
                }

                String command = required(sanitize(stdio1.getCommand()), "stdio.command");

                if ("npx".equalsIgnoreCase(command)) {
                    log.info("检测到 npx 命令，开始验证环境...");
                    log.info("操作系统: {}", System.getProperty("os.name"));

                    String resolvedCommand = resolveNpxCommand();
                    if (resolvedCommand != null) {
                        log.info("找到 npx 可执行文件");
                        command = resolvedCommand;
                    } else {
                        String errorMsg = buildNpxNotFoundError();
                        log.error(errorMsg);
                        throw new RuntimeException(errorMsg);
                    }
                }
                if ("uvx".equalsIgnoreCase(command)) {
                    command = resolveUvxCommand();
                }

                List<String> args = sanitizeList(stdio1.getArgs());
                Map<String, String> env = sanitizeMap(stdio1.getEnv());
                env = enrichMinimaxEnvIfNeeded(env, args);
                var serverParameters = ServerParameters.builder(command)
                        .args(args)
                        .env(env)
                        .build();

                try {
                    var mcpSyncClient = McpClient.sync(new StdioClientTransport(serverParameters))
                            .requestTimeout(Duration.ofSeconds(requestTimeout)).build();
                    var init_result = mcpSyncClient.initialize();
                    log.info("init_result: {}", init_result);
                    return mcpSyncClient;
                } catch (Exception e) {
                    if (shouldRetryWithUvIndex(command, args)) {
                        Map<String, String> retryEnv = new LinkedHashMap<>(env);
                        retryEnv.putIfAbsent("UV_INDEX_URL", "https://pypi.tuna.tsinghua.edu.cn/simple");
                        retryEnv.putIfAbsent("UV_HTTP_TIMEOUT", "60");
                        retryEnv.putIfAbsent("HTTP_PROXY", "");
                        retryEnv.putIfAbsent("HTTPS_PROXY", "");
                        retryEnv.putIfAbsent("ALL_PROXY", "");
                        retryEnv.putIfAbsent("NO_PROXY", "*");
                        var retryParameters = ServerParameters.builder(command)
                                .args(args)
                                .env(retryEnv)
                                .build();
                        var mcpSyncClient = McpClient.sync(new StdioClientTransport(retryParameters))
                                .requestTimeout(Duration.ofSeconds(requestTimeout)).build();
                        var init_result = mcpSyncClient.initialize();
                        log.info("init_result: {}", init_result);
                        return mcpSyncClient;
                    }
                    throw e;
                }
        }
            case "sse"-> {
                AiClientToolMcpVO.TransportConfigSse transportConfigSse = aiClientToolMcpVO.getTransportConfigSse();
                if (transportConfigSse == null) {
                    throw new IllegalArgumentException("sse MCP 配置不能为空");
                }
                SseEndpoint endpoint = resolveSseEndpoint(required(sanitize(transportConfigSse.getBaseUri()), "sse.baseUri"), sanitize(transportConfigSse.getSseEndpoint()));

                HttpClientSseClientTransport httpClientSseClientTransport = HttpClientSseClientTransport.builder(endpoint.baseUrl())
                        .sseEndpoint(endpoint.sseEndpoint())
                        .build();

                McpSyncClient mcpSyncClient = McpClient.sync(httpClientSseClientTransport)
                        .requestTimeout(Duration.ofMinutes(requestTimeout)).build();

                var init_result = mcpSyncClient.initialize();

                log.info("init_result: {}", init_result);
                return mcpSyncClient;
            }
        }

        throw new RuntimeException("err! transportType " + transportType + " not exist!");
    }

    private String required(String value, String name) {
        String checked = value == null ? "" : value.trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return checked;
    }

    private List<String> sanitizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(this::sanitize)
                .filter(v -> v != null && !v.isBlank())
                .toList();
    }

    private Map<String, String> sanitizeMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = sanitize(entry.getKey());
            String value = sanitize(entry.getValue());
            if (key == null || key.isBlank()) {
                continue;
            }
            result.put(key, value == null ? "" : value);
        }
        return result;
    }

    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("`") && v.endsWith("`")) {
            v = v.substring(1, v.length() - 1).trim();
        }
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1).trim();
        }
        return v;
    }

    private boolean shouldRetryWithUvIndex(String command, List<String> args) {
        if (!isUvX(command)) {
            return false;
        }
        if (args == null || args.isEmpty()) {
            return false;
        }
        return args.stream().anyMatch(arg -> "minimax-coding-plan-mcp".equalsIgnoreCase(sanitize(arg)));
    }

    private Map<String, String> enrichMinimaxEnvIfNeeded(Map<String, String> env, List<String> args) {
        if (args == null || args.isEmpty()) {
            return env;
        }
        boolean isMinimaxCodingPlan = args.stream().anyMatch(arg -> "minimax-coding-plan-mcp".equalsIgnoreCase(sanitize(arg)));
        if (!isMinimaxCodingPlan) {
            return env;
        }
        Map<String, String> result = env == null ? new LinkedHashMap<>() : new LinkedHashMap<>(env);
        result.putIfAbsent("MINIMAX_API_HOST", "https://api.minimax.io");
        String apiKey = result.get("MINIMAX_API_KEY");
        if (apiKey != null) {
            String trimmed = apiKey.trim();
            if (trimmed.isEmpty() || trimmed.contains("${") || "MINIMAX_API_KEY".equalsIgnoreCase(trimmed)) {
                result.remove("MINIMAX_API_KEY");
            }
        }
        if (!result.containsKey("MINIMAX_API_KEY")) {
            throw new IllegalStateException("MiniMax MCP 需要配置 env.MINIMAX_API_KEY（当前为空或是占位符），请在 ai_client_tool_mcp.transport_config 中设置真实值");
        }
        return result;
    }

    private boolean isUvX(String command) {
        String v = sanitize(command);
        if (v == null || v.isBlank()) {
            return false;
        }
        String lower = v.toLowerCase();
        return lower.equals("uvx")
                || lower.endsWith("\\uvx.exe")
                || lower.endsWith("/uvx")
                || lower.endsWith("/uvx.exe")
                || lower.endsWith("\\uvx")
                || lower.endsWith("uvx.exe");
    }

    private SseEndpoint resolveSseEndpoint(String baseUri, String configuredEndpoint) {
        URI uri = URI.create(baseUri);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("sse.baseUri 只支持 http/https");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("sse.baseUri 不合法");
        }

        String path = uri.getPath() == null ? "" : uri.getPath();
        String endpoint = configuredEndpoint == null || configuredEndpoint.trim().isEmpty() ? "/sse" : configuredEndpoint.trim();
        String baseUrl = baseUri;
        if ((configuredEndpoint == null || configuredEndpoint.trim().isEmpty()) && path.endsWith("/sse")) {
            endpoint = "/sse";
            String raw = uri.toString();
            baseUrl = raw.substring(0, raw.length() - "/sse".length());
            if (baseUrl.isBlank()) {
                baseUrl = scheme + "://" + uri.getAuthority();
            }
        }
        if (!endpoint.startsWith("/")) {
            endpoint = "/" + endpoint;
        }
        return new SseEndpoint(baseUrl, endpoint);
    }

    private record SseEndpoint(String baseUrl, String sseEndpoint) {
    }

    private String buildNpxNotFoundError() {
        return """
                npx 命令未找到！
                请检查你的环境变量中是否存在 npx 命令。
                或者手动指定 npx 的路径。
                """;
    }

    private String resolveNpxCommand() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        if (isWindows) {
            // 首先尝试通过 where 命令查找（利用已验证的PATH）
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"where.exe", "npx"});
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.toLowerCase().endsWith("npx.cmd") || line.toLowerCase().endsWith("npx.exe")) {
                        File npxFile = new File(line);
                        if (npxFile.exists()) {
                            log.info("通过where命令找到npx: {}", line);
                            return line;
                        }
                    }
                }
                process.waitFor();
            } catch (Exception e) {
                log.debug("通过where命令查找npx失败: {}", e.getMessage());
            }

            // 如果where命令失败，尝试预定义路径
            String userHome = System.getProperty("user.home");

            String[] possiblePaths = {
                    // 标准安装路径
                    System.getenv("ProgramFiles") + "\\nodejs\\npx.cmd",
                    System.getenv("ProgramFiles(x86)") + "\\nodejs\\npx.cmd",
                    "C:\\Program Files\\nodejs\\npx.cmd",
                    "C:\\Program Files (x86)\\nodejs\\npx.cmd",

                    // npm 全局安装路径
                    System.getenv("APPDATA") + "\\npm\\npx.cmd",
                    userHome + "\\AppData\\Roaming\\npm\\npx.cmd",

                    // Node.js 其他可能路径
                    System.getenv("NODE_HOME") != null ? System.getenv("NODE_HOME") + "\\npx.cmd" : null,
            };

            for (String path : possiblePaths) {
                if (path != null && new File(path).exists()) {
                    log.info("在预定义路径中找到npx: {}", path);
                    return path;
                }
            }
        } else {
            // Unix/Linux/macOS 系统
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"which", "npx"});
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()));
                String line = reader.readLine();
                if (line != null && !line.isEmpty()) {
                    File npxFile = new File(line);
                    if (npxFile.exists()) {
                        log.info("通过which命令找到npx: {}", line);
                        return line;
                    }
                }
                process.waitFor();
            } catch (Exception e) {
                log.debug("通过which命令查找npx失败: {}", e.getMessage());
            }
        }

        log.warn("未找到npx可执行文件");
        return null;
    }

    private String resolveUvxCommand() {
        String resolved = resolveCommand("uvx", "uvx.exe");
        if (resolved != null) {
            return resolved;
        }
        throw new RuntimeException("uvx 命令未找到，请安装 uv 或在 ai_client_tool_mcp.transport_config 中配置 uvx.exe 绝对路径");
    }

    private String resolveCommand(String command, String windowsExecutableName) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            String[] candidates = {
                    command,
                    command + ".exe",
                    Path.of(System.getProperty("user.home"), ".local", "bin", windowsExecutableName).toString(),
                    Path.of(System.getProperty("user.home"), "AppData", "Roaming", "Python", "Scripts", windowsExecutableName).toString(),
                    System.getenv("LOCALAPPDATA") == null ? null : Path.of(System.getenv("LOCALAPPDATA"), "Programs", "Python", "Python312", "Scripts", windowsExecutableName).toString()
            };
            for (String candidate : candidates) {
                String resolved = resolveWindowsCommand(candidate, windowsExecutableName);
                if (resolved != null) {
                    log.info("找到 {} 可执行文件", command);
                    return resolved;
                }
            }
            return null;
        }
        String resolved = resolveUnixCommand(command);
        if (resolved != null) {
            log.info("找到 {} 可执行文件", command);
        }
        return resolved;
    }

    private String resolveWindowsCommand(String command, String executableName) {
        if (command == null || command.isBlank()) {
            return null;
        }
        File direct = new File(command);
        if (direct.exists()) {
            return direct.getAbsolutePath();
        }
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"where.exe", command});
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.toLowerCase().endsWith(executableName.toLowerCase())) {
                    File file = new File(line);
                    if (file.exists()) {
                        return line;
                    }
                }
            }
            process.waitFor();
        } catch (Exception e) {
            log.debug("通过 where 查找命令失败, command={}, error={}", command, e.getMessage());
        }
        return null;
    }

    private String resolveUnixCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"which", command});
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            if (line != null && !line.isEmpty()) {
                File file = new File(line);
                if (file.exists()) {
                    return line;
                }
            }
            process.waitFor();
        } catch (Exception e) {
            log.debug("通过 which 查找命令失败, command={}, error={}", command, e.getMessage());
        }
        return null;
    }

    private boolean isCommandAvailable(String npx) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"which", npx});
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
