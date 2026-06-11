package cn.apple.application.workflow;

import cn.apple.application.agent.AiClientRuntimeService;
import cn.apple.application.multimodal.MultimodalService;
import cn.apple.domain.workflow.agent.Agent;
import cn.apple.domain.workflow.model.AgentContext;
import cn.apple.domain.workflow.model.AgentResult;
import cn.apple.domain.workflow.model.Citation;
import cn.apple.domain.workflow.model.RiskItem;
import cn.apple.domain.workflow.model.RiskLevel;
import cn.apple.domain.workflow.model.WorkflowAttachment;
import cn.apple.domain.workflow.model.WorkflowDefinition;
import cn.apple.domain.workflow.model.WorkflowEdge;
import cn.apple.domain.workflow.model.WorkflowEvent;
import cn.apple.domain.workflow.model.WorkflowNode;
import cn.apple.domain.workflow.model.WorkflowNodeType;
import cn.apple.domain.workflow.model.WorkflowRunResult;
import cn.apple.domain.workflow.service.WorkflowEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LegalOrchestratorService {

    private final AiClientRuntimeService aiClientRuntimeService;
    private final VectorStore vectorStore;
    private final MultimodalService multimodalService;
    private final WorkflowEngine workflowEngine = new WorkflowEngine();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LegalOrchestratorService(AiClientRuntimeService aiClientRuntimeService, VectorStore vectorStore, MultimodalService multimodalService) {
        this.aiClientRuntimeService = aiClientRuntimeService;
        this.vectorStore = vectorStore;
        this.multimodalService = multimodalService;
    }

    public WorkflowDefinition defaultWorkflow() {
        String workflowId = "legal-workflow-v1";
        List<WorkflowNode> nodes = List.of(
                new WorkflowNode("start", WorkflowNodeType.START, "开始", Map.of()),
                new WorkflowNode("intent", WorkflowNodeType.INTENT, "意图识别", Map.of()),
                new WorkflowNode("ocr", WorkflowNodeType.OCR, "材料解析", Map.of()),
                new WorkflowNode("contract", WorkflowNodeType.CONTRACT, "合同结构化", Map.of()),
                new WorkflowNode("retrieval", WorkflowNodeType.RETRIEVAL, "法条/案例检索", Map.of("topK", 6)),
                new WorkflowNode("reasoning", WorkflowNodeType.REASONING, "法律推理", Map.of()),
                new WorkflowNode("risk", WorkflowNodeType.RISK, "风险分析", Map.of()),
                new WorkflowNode("citation", WorkflowNodeType.CITATION, "引用整理", Map.of()),
                new WorkflowNode("report", WorkflowNodeType.REPORT, "报告生成", Map.of()),
                new WorkflowNode("end", WorkflowNodeType.END, "结束", Map.of())
        );
        List<WorkflowEdge> edges = List.of(
                new WorkflowEdge("start", "intent", null),
                new WorkflowEdge("intent", "ocr", null),
                new WorkflowEdge("ocr", "contract", null),
                new WorkflowEdge("contract", "retrieval", null),
                new WorkflowEdge("retrieval", "reasoning", null),
                new WorkflowEdge("reasoning", "risk", null),
                new WorkflowEdge("risk", "citation", null),
                new WorkflowEdge("citation", "report", null),
                new WorkflowEdge("report", "end", null)
        );
        return new WorkflowDefinition(workflowId, "企业级法律顾问工作流", "start", "end", nodes, edges, Map.of());
    }

    public WorkflowRunResult run(String clientId,
                                 String message,
                                 Map<String, Object> variables,
                                 List<WorkflowAttachment> attachments,
                                 Consumer<WorkflowEvent> eventSink) throws Exception {
        String runId = UUID.randomUUID().toString().replace("-", "");
        AgentContext base = new AgentContext(
                defaultWorkflow().workflowId(),
                runId,
                clientId,
                message,
                message,
                attachments == null ? List.of() : attachments,
                Map.of(),
                variables == null ? Map.of() : variables,
                LocalDateTime.now()
        );
        Map<WorkflowNodeType, Agent> agents = buildAgents(clientId);
        return workflowEngine.run(defaultWorkflow(), base, agents, eventSink == null ? ignored -> {} : eventSink);
    }

    private Map<WorkflowNodeType, Agent> buildAgents(String clientId) {
        Map<WorkflowNodeType, Agent> map = new LinkedHashMap<>();
        map.put(WorkflowNodeType.INTENT, new IntentAgent(clientId));
        map.put(WorkflowNodeType.OCR, new OcrAgent(clientId));
        map.put(WorkflowNodeType.CONTRACT, new ContractAgent(clientId));
        map.put(WorkflowNodeType.RETRIEVAL, new RetrievalAgent(clientId));
        map.put(WorkflowNodeType.REASONING, new ReasoningAgent(clientId));
        map.put(WorkflowNodeType.RISK, new RiskAgent(clientId));
        map.put(WorkflowNodeType.CITATION, new CitationAgent());
        map.put(WorkflowNodeType.REPORT, new ReportAgent(clientId));
        return map;
    }

    private final class PassThroughAgent implements Agent {
        private final String name;

        private PassThroughAgent(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.ofText(context.inputText());
        }
    }

    private final class OcrAgent implements Agent {
        private final String clientId;

        private OcrAgent(String clientId) {
            this.clientId = clientId;
        }

        @Override
        public String name() {
            return "ocr";
        }

        @Override
        public AgentResult execute(AgentContext context) throws Exception {
            if (context.attachments() == null || context.attachments().isEmpty()) {
                return AgentResult.ofText(context.inputText());
            }
            WorkflowAttachment attachment = context.attachments().get(0);
            String contentType = attachment.contentType() == null ? "" : attachment.contentType().trim().toLowerCase();
            String localPath = attachment.localPath() == null ? "" : attachment.localPath().trim();
            if (localPath.isEmpty()) {
                return AgentResult.ofText(context.inputText());
            }
            if (contentType.startsWith("image/")) {
                String imageText = multimodalService.recognizeImage(clientId,
                        "Please extract visible text, main content, key facts, possible legal risks, and contract-review points from this image. Respond in Simplified Chinese.",
                        java.nio.file.Path.of(localPath));
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put("ocrText", imageText);
                vars.put("imageRecognition", imageText);
                return new AgentResult(imageText, List.of(), List.of(), vars);
            }

            TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(localPath));
            List<Document> docs = reader.get();
            String text = (docs == null ? List.<Document>of() : docs).stream()
                    .map(Document::getText)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.joining("\n\n"));
            if (text.isBlank()) {
                text = context.inputText();
            }
            return new AgentResult(text, List.of(), List.of(), Map.of("ocrText", text));
        }
    }

    private final class ContractAgent implements Agent {
        private final String clientId;

        private ContractAgent(String clientId) {
            this.clientId = clientId;
        }

        @Override
        public String name() {
            return "contract";
        }

        @Override
        public AgentResult execute(AgentContext context) throws Exception {
            String input = context.inputText() == null ? "" : context.inputText().trim();
            if (input.isBlank()) {
                return AgentResult.ofText(context.inputText());
            }
            String system = """
                    你是合同结构化智能体。
                    任务：将输入合同文本按条款进行结构化切分，尽量以“第X条/第X条之Y/条款标题/款项”作为边界。
                    输出必须是 JSON 数组，每项字段：clauseNo, title, content。
                    如果不是合同文本，也要输出一个单元素数组，title 为“原文”。
                    """;
            ChatClient chatClient = aiClientRuntimeService.getChatClient(clientId);
            String raw = chatClient.prompt(system + "\n\n用户输入：\n" + input).call().content();
            String json = ensureJsonArray(chatClient, raw, """
                    你需要把下面内容修正为 JSON 数组（不能是 Markdown，不能有代码块标记），数组元素字段：clauseNo,title,content。
                    如果内容不是数组，请包装成单元素数组，title=原文，content=原文。
                    """, List.of(
                    Map.of("clauseNo", "原文", "title", "原文", "content", input)
            ));
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("contractClausesJson", json);
            vars.put("contractContext", safeSlice(input, 1600));
            return new AgentResult(context.inputText(), List.of(), List.of(), vars);
        }

        private String safeSlice(String text, int max) {
            String t = text == null ? "" : text.trim();
            if (t.length() <= max) return t;
            return t.substring(0, max);
        }
    }

    private final class IntentAgent implements Agent {
        private final String clientId;

        private IntentAgent(String clientId) {
            this.clientId = clientId;
        }

        @Override
        public String name() {
            return "intent";
        }

        @Override
        public AgentResult execute(AgentContext context) throws Exception {
            ChatClient chatClient = aiClientRuntimeService.getChatClient(clientId);
            String system = """
                    你是企业级法律顾问平台的意图识别智能体。
                    任务：把用户输入归类为咨询/合同审查/尽调/合规风控/诉讼仲裁/证据分析/其他，并输出一个简短的分类结果与后续建议。
                    输出必须是 JSON，字段：intent, summary, suggestedWorkflow。
                    """;
            String raw = chatClient.prompt(system + "\n\n用户输入：\n" + context.userMessage()).call().content();
            String json = ensureJsonObject(chatClient, raw, """
                    你需要把下面内容修正为 JSON 对象（不能是 Markdown，不能有代码块标记），且必须包含字段：intent, summary, suggestedWorkflow。
                    intent 只能取：咨询/合同审查/尽调/合规风控/诉讼仲裁/证据分析/其他。
                    """, Map.of(
                    "intent", "其他",
                    "summary", safeSlice(context.userMessage(), 240),
                    "suggestedWorkflow", "legal-workflow-v1"
            ));
            return new AgentResult(context.inputText(), List.of(), List.of(), Map.of("intent", json));
        }
    }

    private final class RetrievalAgent implements Agent {
        private final String clientId;

        private RetrievalAgent(String clientId) {
            this.clientId = clientId;
        }

        @Override
        public String name() {
            return "retrieval";
        }

        @Override
        public AgentResult execute(AgentContext context) throws Exception {
            int topK = 6;
            Object topKValue = context.variables() == null ? null : context.variables().get("topK");
            if (topKValue instanceof Number n) {
                topK = Math.max(1, Math.min(20, n.intValue()));
            }
            String knowledgeTag = "law-consultant";
            Object tagValue = context.variables() == null ? null : context.variables().get("knowledgeTag");
            if (tagValue != null && !tagValue.toString().isBlank()) {
                knowledgeTag = tagValue.toString().trim();
            }
            String contractContext = context.variables() != null && context.variables().get("contractContext") != null
                    ? context.variables().get("contractContext").toString()
                    : "";
            String query = context.userMessage();
            if (!contractContext.isBlank()) {
                query = query + "\n\n合同摘要（可能不完整）：\n" + contractContext;
            }

            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .filterExpression("knowledge == '" + knowledgeTag.replace("'", "") + "'")
                    .build();
            List<Document> docs;
            String retrievalError = null;
            try {
                docs = vectorStore.similaritySearch(request);
                if (docs == null) {
                    docs = List.of();
                }
            } catch (Exception e) {
                docs = List.of();
                retrievalError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            List<Citation> citations = (docs == null ? List.<Document>of() : docs).stream().map(d -> {
                String lawName = stringMeta(d, "lawName", stringMeta(d, "law_name", ""));
                String articleNo = stringMeta(d, "articleNo", stringMeta(d, "article_no", ""));
                String clauseNo = stringMeta(d, "clauseNo", stringMeta(d, "clause_no", ""));
                String source = stringMeta(d, "source", stringMeta(d, "sourcePath", ""));
                String title = (lawName == null || lawName.isBlank()) ? "法律条文" : lawName;
                String excerpt = safeExcerpt(d.getText(), 360);
                return new Citation(title, source, articleNo, clauseNo, excerpt);
            }).toList();

            String contextText = (docs == null ? List.<Document>of() : docs).stream()
                    .map(Document::getText)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.joining("\n\n---\n\n"));

            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("ragContext", contextText);
            vars.put("citations", citations);
            if (retrievalError != null) {
                vars.put("ragError", retrievalError);
            }
            return new AgentResult(context.inputText(), citations, List.of(), vars);
        }

        private String safeExcerpt(String value, int max) {
            String t = value == null ? "" : value.trim();
            if (t.length() <= max) return t;
            return t.substring(0, max);
        }

        private String stringMeta(Document doc, String key, String fallback) {
            Object v = doc.getMetadata().get(key);
            if (v == null) return fallback;
            String s = v.toString();
            return s.isBlank() ? fallback : s;
        }
    }

    private final class ReasoningAgent implements Agent {
        private final String clientId;

        private ReasoningAgent(String clientId) {
            this.clientId = clientId;
        }

        @Override
        public String name() {
            return "reasoning";
        }

        @Override
        public AgentResult execute(AgentContext context) throws Exception {
            ChatClient chatClient = aiClientRuntimeService.getChatClient(clientId);
            String ragContext = context.variables() != null && context.variables().get("ragContext") != null
                    ? context.variables().get("ragContext").toString()
                    : "";
            String contractContext = context.variables() != null && context.variables().get("contractContext") != null
                    ? context.variables().get("contractContext").toString()
                    : "";
            String system = """
                    你是企业级法律推理智能体。
                    只允许基于给定的法条/案例材料进行推理，不允许编造法条与案例。
                    输出结构：
                    1) 结论摘要
                    2) 推理链（分点）
                    3) 需要补充的关键事实
                    4) 免责声明（非正式法律意见）
                    """;
            String user = "用户问题：\n" + context.userMessage()
                    + "\n\n合同材料（可能不完整）：\n" + contractContext
                    + "\n\n可引用材料（RAG）：\n" + ragContext;
            String out = chatClient.prompt(system + "\n\n" + user).call().content();
            return new AgentResult(out, List.of(), List.of(), Map.of("reasoning", out));
        }
    }

    private final class RiskAgent implements Agent {
        private final String clientId;

        private RiskAgent(String clientId) {
            this.clientId = clientId;
        }

        @Override
        public String name() {
            return "risk";
        }

        @Override
        public AgentResult execute(AgentContext context) throws Exception {
            ChatClient chatClient = aiClientRuntimeService.getChatClient(clientId);
            String system = """
                    你是企业合规与合同风险智能体。
                    基于输入内容输出风险清单（3-8条），每条包含：title、level(HIGH/MEDIUM/LOW)、description、suggestion。
                    输出必须是 JSON 数组。
                    """;
            String raw = chatClient.prompt(system + "\n\n输入内容：\n" + context.inputText()).call().content();
            String json = ensureJsonArray(chatClient, raw, """
                    你需要把下面内容修正为 JSON 数组（不能是 Markdown，不能有代码块标记）。
                    每项字段：title, level(HIGH/MEDIUM/LOW), description, suggestion。
                    """, List.of(
                    Map.of(
                            "title", "风险清单",
                            "level", "MEDIUM",
                            "description", safeSlice(context.inputText(), 360),
                            "suggestion", "请根据业务实际进一步核实"
                    )
            ));
            List<RiskItem> items = List.of(new RiskItem("风险清单", RiskLevel.MEDIUM, json, "请根据业务实际进一步核实"));
            return new AgentResult(context.inputText(), List.of(), items, Map.of("riskJson", json));
        }
    }

    private final class CitationAgent implements Agent {
        @Override
        public String name() {
            return "citation";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            Object c = context.variables() == null ? null : context.variables().get("citations");
            List<Citation> list = c instanceof List<?> raw ? raw.stream().filter(x -> x instanceof Citation).map(x -> (Citation) x).toList() : List.of();
            String md = list.isEmpty()
                    ? "（本次未检索到可引用法条/案例）"
                    : list.stream().map(it -> "- " + it.title() + " " + (it.articleNo() == null ? "" : it.articleNo()) + " " + (it.clauseNo() == null ? "" : it.clauseNo()) + (it.source() == null || it.source().isBlank() ? "" : ("（" + it.source() + "）"))).collect(Collectors.joining("\n"));
            return new AgentResult(context.inputText(), list, List.of(), Map.of("citationsMd", md));
        }
    }

    private final class ReportAgent implements Agent {
        private final String clientId;

        private ReportAgent(String clientId) {
            this.clientId = clientId;
        }

        @Override
        public String name() {
            return "report";
        }

        @Override
        public AgentResult execute(AgentContext context) throws Exception {
            ChatClient chatClient = aiClientRuntimeService.getChatClient(clientId);
            String reasoning = context.variables() != null && context.variables().get("reasoning") != null
                    ? context.variables().get("reasoning").toString()
                    : context.inputText();
            String intentJson = context.variables() != null && context.variables().get("intent") != null
                    ? context.variables().get("intent").toString()
                    : "";
            String riskJson = context.variables() != null && context.variables().get("riskJson") != null
                    ? context.variables().get("riskJson").toString()
                    : "";
            String citationsMd = context.variables() != null && context.variables().get("citationsMd") != null
                    ? context.variables().get("citationsMd").toString()
                    : "";
            String system = """
                    你是企业级法律顾问报告生成智能体。
                    输出 Markdown 报告，包含：
                    - 标题与基本信息
                    - 意图与范围
                    - 事实与假设
                    - 法律分析（含推理链）
                    - 风险清单（分级）
                    - 引用与来源（若无引用也要说明）
                    - 建议与下一步
                    - 免责声明
                    """;
            String user = "意图(JSON)：\n" + intentJson
                    + "\n\n推理内容：\n" + reasoning
                    + "\n\n风险(JSON)：\n" + riskJson
                    + "\n\n引用（Markdown）：\n" + citationsMd;
            String out = chatClient.prompt(system + "\n\n" + user).call().content();
            return new AgentResult(out, List.of(), List.of(), Map.of("report", out));
        }
    }

    private String ensureJsonObject(ChatClient chatClient, String raw, String repairInstruction, Map<String, Object> fallback) {
        String candidate = extractJsonCandidate(raw);
        Map<String, Object> parsed = parseJsonObject(candidate);
        if (parsed != null) {
            return candidate;
        }
        String repaired = chatClient.prompt(repairInstruction + "\n\n原始内容：\n" + raw).call().content();
        String repairedCandidate = extractJsonCandidate(repaired);
        Map<String, Object> repairedParsed = parseJsonObject(repairedCandidate);
        if (repairedParsed != null) {
            return repairedCandidate;
        }
        return writeJson(fallback);
    }

    private String ensureJsonArray(ChatClient chatClient, String raw, String repairInstruction, List<Map<String, Object>> fallback) {
        String candidate = extractJsonCandidate(raw);
        List<Map<String, Object>> parsed = parseJsonArray(candidate);
        if (parsed != null) {
            return candidate;
        }
        String repaired = chatClient.prompt(repairInstruction + "\n\n原始内容：\n" + raw).call().content();
        String repairedCandidate = extractJsonCandidate(repaired);
        List<Map<String, Object>> repairedParsed = parseJsonArray(repairedCandidate);
        if (repairedParsed != null) {
            return repairedCandidate;
        }
        return writeJson(fallback);
    }

    private Map<String, Object> parseJsonObject(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> parseJsonArray(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String extractJsonCandidate(String value) {
        String text = value == null ? "" : value.trim();
        int objStart = text.indexOf('{');
        int objEnd = text.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            return text.substring(objStart, objEnd + 1).trim();
        }
        int arrStart = text.indexOf('[');
        int arrEnd = text.lastIndexOf(']');
        if (arrStart >= 0 && arrEnd > arrStart) {
            return text.substring(arrStart, arrEnd + 1).trim();
        }
        return text;
    }

    private String safeSlice(String value, int max) {
        String t = value == null ? "" : value.trim();
        if (t.length() <= max) return t;
        return t.substring(0, max);
    }
}
