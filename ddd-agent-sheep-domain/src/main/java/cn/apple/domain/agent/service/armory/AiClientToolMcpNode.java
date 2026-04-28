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
import java.time.Duration;
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

        for (AiClientToolMcpVO aiClientToolMcpVO : aiClientToolMcpVOS){

            McpSyncClient mcpSyncClient = createMcpSyncClient(aiClientToolMcpVO);

            registerBean(beanName(aiClientToolMcpVO.getMcpId()), McpSyncClient.class, mcpSyncClient);
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
        String transportType = aiClientToolMcpVO.getTransportType();
        switch (transportType) {
            case "stdio" -> {
                AiClientToolMcpVO.TransportConfigStdio transportConfigStdio = aiClientToolMcpVO.getTransportConfigStdio();
                Map<String, AiClientToolMcpVO.TransportConfigStdio.Stdio> stdio = transportConfigStdio.getStdio();
                AiClientToolMcpVO.TransportConfigStdio.Stdio stdio1 = stdio.get(aiClientToolMcpVO.getMcpName());

                String command = stdio1.getCommand();

                if ("npx".equals(command)) {
                    log.info("检测到 npx 命令，开始验证环境...");
                    log.info("操作系统: {}", System.getProperty("os.name"));
                    log.info("当前 PATH: {}", System.getenv("PATH"));

                    String resolvedCommand = resolveNpxCommand();
                    if (resolvedCommand != null) {
                        log.info("找到 npx 可执行文件: {}", resolvedCommand);
                        command = resolvedCommand;
                    } else {
                        String errorMsg = buildNpxNotFoundError();
                        log.error(errorMsg);
                        throw new RuntimeException(errorMsg);
                    }
                }

                // 使用解析后的 command，而不是 stdio1.getCommand()
                var serverParameters = ServerParameters.builder(command)
                        .args(stdio1.getArgs())
                        .env(stdio1.getEnv())
                        .build();

                var mcpSyncClient = McpClient.sync(new StdioClientTransport(serverParameters))
                        .requestTimeout(Duration.ofSeconds(aiClientToolMcpVO.getRequestTimeout())).build();

                var init_result = mcpSyncClient.initialize();
                log.info("init_result: {}", init_result);

                return mcpSyncClient;
        }
            case "sse"-> {
                AiClientToolMcpVO.TransportConfigSse transportConfigSse = aiClientToolMcpVO.getTransportConfigSse();
                String ConfigBaseUri = transportConfigSse.getBaseUri();
                String baseUrl;
                String sseEndpoint;

                int pos = ConfigBaseUri.indexOf("sse");
                if (pos != -1) {
                    baseUrl = ConfigBaseUri.substring(0, pos - 1);
                    sseEndpoint = ConfigBaseUri.substring(pos - 1);
                } else {
                    baseUrl = ConfigBaseUri;
                    sseEndpoint = transportConfigSse.getSseEndpoint();
                }

                sseEndpoint = sseEndpoint.isBlank() ? "/sse" : sseEndpoint;

                HttpClientSseClientTransport httpClientSseClientTransport = HttpClientSseClientTransport.builder(baseUrl)
                        .sseEndpoint(sseEndpoint)
                        .build();

                McpSyncClient mcpSyncClient = McpClient.sync(httpClientSseClientTransport)
                        .requestTimeout(Duration.ofMinutes(aiClientToolMcpVO.getRequestTimeout())).build();

                var init_result = mcpSyncClient.initialize();

                log.info("init_result: {}", init_result);
                return mcpSyncClient;
            }
        }

        throw new RuntimeException("err! transportType " + transportType + " not exist!");
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
