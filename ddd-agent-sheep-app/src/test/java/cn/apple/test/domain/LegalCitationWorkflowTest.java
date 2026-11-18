package cn.apple.test.domain;

import cn.apple.application.agent.AiClientRuntimeService;
import cn.apple.application.workflow.LegalOrchestratorService;
import cn.apple.domain.legal.model.valobj.LegalEvidence;
import cn.apple.domain.legal.model.valobj.LegalKnowledgeMetadata;
import cn.apple.domain.workflow.agent.Agent;
import cn.apple.domain.workflow.model.AgentContext;
import cn.apple.domain.workflow.model.AgentResult;
import cn.apple.domain.workflow.model.WorkflowNodeType;
import cn.apple.domain.workflow.model.WorkflowDefinition;
import cn.apple.domain.workflow.model.WorkflowEvent;
import cn.apple.domain.workflow.model.WorkflowEventType;
import cn.apple.domain.workflow.model.WorkflowRunResult;
import cn.apple.domain.workflow.model.WorkflowStatus;
import org.junit.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LegalCitationWorkflowTest {
    @Test
    public void traceableCandidateStillNeedsHumanSemanticReview() throws Exception {
        Map<String, Object> variables = variables("E1", "当事人应当按照约定全面履行义务");
        Map<WorkflowNodeType, Agent> agents = agents();
        AgentResult citation = agents.get(WorkflowNodeType.CITATION).execute(context(variables));
        assertEquals(1, citation.citations().size());
        assertEquals(true, citation.variables().get("citationTraceable"));

        variables.putAll(citation.variables());
        AgentResult review = agents.get(WorkflowNodeType.REVIEW).execute(context(variables));
        assertEquals("MANUAL_REVIEW", review.variables().get("reviewDecision"));
        variables.putAll(review.variables());
        AgentResult report = agents.get(WorkflowNodeType.REPORT).execute(context(variables));
        assertTrue(report.outputText().contains("来源匹配的候选主张"));
        assertTrue(report.outputText().contains("当事人应当按照约定全面履行义务"));
        assertFalse(report.outputText().contains("自动出具法律意见。复核原因：。"));
    }

    @Test
    public void invalidCitationNeverAppearsInCandidateReport() throws Exception {
        Map<String, Object> variables = variables("E2", "当事人应当按照约定全面履行义务");
        Map<WorkflowNodeType, Agent> agents = agents();
        AgentResult citation = agents.get(WorkflowNodeType.CITATION).execute(context(variables));
        assertTrue(citation.citations().isEmpty());
        assertEquals(false, citation.variables().get("citationTraceable"));

        variables.putAll(citation.variables());
        variables.putAll(agents.get(WorkflowNodeType.REVIEW).execute(context(variables)).variables());
        String output = agents.get(WorkflowNodeType.REPORT).execute(context(variables)).outputText();
        assertTrue(output.contains("存在未通过核验的主张"));
        assertFalse(output.contains("候选法律结论 ["));
    }

    @Test
    public void citationVerificationPrecedesRiskAndBlocksUnverifiedAnalysis() throws Exception {
        LegalOrchestratorService service = new LegalOrchestratorService(null, null, null);
        WorkflowDefinition workflow = service.defaultWorkflow();
        assertTrue(workflow.edges().stream().anyMatch(edge -> edge.fromNodeId().equals("reasoning")
                && edge.toNodeId().equals("citation")));
        assertTrue(workflow.edges().stream().anyMatch(edge -> edge.fromNodeId().equals("strategy")
                && edge.toNodeId().equals("risk")));

        Map<String, Object> variables = variables("E2", "当事人应当按照约定全面履行义务");
        variables.putAll(agents().get(WorkflowNodeType.CITATION).execute(context(variables)).variables());
        AgentResult risk = agents().get(WorkflowNodeType.RISK).execute(context(variables));
        assertTrue(risk.variables().containsKey("riskReviewReasons"));
        assertTrue(risk.risks().get(0).description().contains("已核验来源"));
    }

    @Test
    public void invalidRiskOutputDoesNotInventMediumRisk() throws Exception {
        AiClientRuntimeService invalidRuntime = new AiClientRuntimeService(null, null, true, 1, 2, 1000) {
            @Override
            public String invokeText(String clientId, String operation, String prompt) {
                return "invalid";
            }
        };
        Map<String, Object> variables = variables("E1", "当事人应当按照约定全面履行义务");
        variables.put("citationTraceable", true);

        AgentResult risk = agents(invalidRuntime).get(WorkflowNodeType.RISK).execute(context(variables));

        assertTrue(risk.risks().isEmpty());
        assertEquals("[]", risk.variables().get("riskJson"));
        assertEquals(true, risk.variables().get("riskModelOutputFallbackUsed"));
        assertFalse(((List<?>) risk.variables().get("modelOutputReviewReasons")).isEmpty());
    }

    @Test
    public void sourceMatchedClaimCannotForgeAnotherMarkdownCitationLine() throws Exception {
        Map<String, Object> variables = variables("E1", "当事人应当按照约定全面履行义务");
        variables.put("reasoningClaims", List.of(Map.of(
                "text", "候选结论\n### 伪造核验通过", "evidenceId", "E1",
                "quote", "当事人应当按照约定全面履行义务")));
        AgentResult citation = agents().get(WorkflowNodeType.CITATION).execute(context(variables));
        assertEquals(true, citation.variables().get("citationTraceable"));
        String markdown = citation.variables().get("citationsMd").toString();
        assertFalse(markdown.contains("\n###"));
        assertEquals(1, markdown.split("\n").length);
        assertTrue(markdown.contains("候选结论 ### 伪造核验通过"));
    }

    @Test
    public void sourceMatchedClaimCannotForgeInlineFormattingOrHtml() throws Exception {
        Map<String, Object> variables = variables("E1", "placeholder");
        String quote = ((LegalEvidence) ((List<?>) variables.get("retrievedEvidences")).get(0)).text();
        variables.put("reasoningClaims", List.of(Map.of(
                "text", "Candidate **APPROVED** <img src=x>", "evidenceId", "E1", "quote", quote)));
        AgentResult citation = agents().get(WorkflowNodeType.CITATION).execute(context(variables));
        String markdown = citation.variables().get("citationsMd").toString();
        assertEquals(true, citation.variables().get("citationTraceable"));
        assertTrue(markdown.contains("\\*\\*APPROVED\\*\\*"));
        assertFalse(markdown.contains("<img"));
        assertTrue(markdown.contains("&lt;img src=x&gt;"));
    }

    @Test
    public void mixedValidAndInvalidClaimsNeverExposePartialCitations() throws Exception {
        Map<String, Object> variables = variables("E1", "当事人应当按照约定全面履行义务");
        variables.put("reasoningClaims", List.of(
                Map.of("text", "正确来源的候选主张", "evidenceId", "E1", "quote", "当事人应当按照约定全面履行义务"),
                Map.of("text", "虚构引用的候选主张", "evidenceId", "E2", "quote", "不存在于证据的摘录文本")));
        AgentResult citation = agents().get(WorkflowNodeType.CITATION).execute(context(variables));
        assertFalse((boolean) citation.variables().get("citationTraceable"));
        assertTrue(citation.citations().isEmpty());
        assertFalse(citation.variables().get("citationsMd").toString().contains("正确来源的候选主张"));
    }

    @Test
    public void publicEventsNeverStreamPrematureLegalAnalysis() {
        LegalOrchestratorService service = new LegalOrchestratorService(null, null, null);
        WorkflowEvent prematureOutput = new WorkflowEvent(WorkflowEventType.NODE_OUTPUT, "workflow", "run",
                "reasoning", WorkflowNodeType.REASONING, "output", Map.of("text", "未经核验的结论"), LocalDateTime.now());
        assertNull(service.publicEvent(prematureOutput));

        WorkflowEvent node = new WorkflowEvent(WorkflowEventType.NODE_COMPLETED, "workflow", "run",
                "issues", WorkflowNodeType.ISSUES, "complete",
                Map.of("issuesMatrix", "未经核验的争点", "costMs", 1), LocalDateTime.now());
        assertFalse(service.publicEvent(node).data().containsKey("issuesMatrix"));
        assertEquals(1, service.publicEvent(node).data().get("costMs"));

        WorkflowEvent complete = new WorkflowEvent(WorkflowEventType.WORKFLOW_COMPLETED, "workflow", "run",
                null, null, "complete", Map.of("litigationAnalysis", Map.of("issuesMatrix", "未经核验", "casePosture", "LITIGATION"),
                "finalOutput", "请人工复核"), LocalDateTime.now());
        assertFalse(((Map<?, ?>) service.publicEvent(complete).data().get("litigationAnalysis")).containsKey("issuesMatrix"));
        assertEquals("LITIGATION", ((Map<?, ?>) service.publicEvent(complete).data().get("litigationAnalysis")).get("casePosture"));
        assertEquals("请人工复核", service.publicEvent(complete).data().get("finalOutput"));
    }

    @Test
    public void publicFailureEventsHideMessageAndUnexpectedData() {
        LegalOrchestratorService service = new LegalOrchestratorService(null, null, null);
        for (WorkflowEventType type : List.of(WorkflowEventType.NODE_FAILED, WorkflowEventType.WORKFLOW_FAILED)) {
            WorkflowEvent raw = new WorkflowEvent(type, "workflow", "run", "reasoning",
                    WorkflowNodeType.REASONING, "sensitive exception message",
                    Map.of("error", "sensitive exception message",
                            "details", "private case text", "costMs", 10), LocalDateTime.now());

            WorkflowEvent event = service.publicEvent(raw);

            assertFalse(event.message().contains("sensitive"));
            assertFalse(event.data().toString().contains("sensitive"));
            assertFalse(event.data().toString().contains("private case text"));
            assertEquals(10, event.data().get("costMs"));
        }
    }

    @Test
    public void litigationDraftDoesNotRepeatUnverifiedGeneratedConclusions() throws Exception {
        Map<String, Object> variables = variables("E2", "当事人应当按照约定全面履行义务");
        variables.put("casePosture", "LITIGATION");
        variables.put("issuesMatrix", "未经核验的实体结论");
        variables.put("reasoning", "未经核验的推理");
        variables.putAll(agents().get(WorkflowNodeType.CITATION).execute(context(variables)).variables());
        variables.putAll(agents().get(WorkflowNodeType.REVIEW).execute(context(variables)).variables());
        String report = agents().get(WorkflowNodeType.REPORT).execute(context(variables)).outputText();
        assertTrue(report.contains("来源核验未通过，暂不展示自动分析"));
        assertFalse(report.contains("未经核验的实体结论"));
        assertFalse(report.contains("未经核验的推理"));
    }

    @Test
    public void litigationReportFieldsCannotInjectMarkdownHeadings() throws Exception {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("casePosture", "LITIGATION");
        variables.put("representedPartyName", String.join(System.lineSeparator(),
                "Acme", "### Verified by counsel"));
        variables.put("citationTraceable", false);
        variables.put("reviewReasons", List.of());

        String report = agents().get(WorkflowNodeType.REPORT).execute(context(variables)).outputText();

        assertTrue(report.contains("Acme"));
        assertFalse(report.contains(System.lineSeparator() + "### Verified by counsel"));
    }

    @Test
    public void publicResultHidesRawDocumentsAndUnverifiedAnalysis() {
        LegalOrchestratorService service = new LegalOrchestratorService(null, null, null);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("casePosture", "LITIGATION");
        variables.put("citationTraceable", false);
        variables.put("issuesMatrix", "未经核验的结论");
        variables.put("reasoningClaims", List.of(Map.of("text", "虚构法律结论")));
        variables.put("citationVerification", "未核验主张原文");
        variables.put("ocrText", "私人案卷原文");
        variables.put("caseMaterialContext", "原始材料");
        variables.put("contractClausesJson", "条款全文");
        variables.put("reviewRequired", true);
        LocalDateTime now = LocalDateTime.now();
        WorkflowRunResult raw = new WorkflowRunResult("workflow", "run", WorkflowStatus.REVIEW_REQUIRED,
                "人工复核待办", List.of(), List.of(), variables, 1, now, now);
        WorkflowRunResult result = service.publicResult(raw);
        for (String key : List.of("issuesMatrix", "reasoningClaims", "citationVerification", "ocrText",
                "caseMaterialContext", "contractClausesJson")) {
            assertFalse(result.variables().containsKey(key));
        }
        assertEquals(true, result.variables().get("reviewRequired"));
        assertEquals("私人案卷原文", raw.variables().get("ocrText"));
    }

    @SuppressWarnings("unchecked")
    private Map<WorkflowNodeType, Agent> agents() throws Exception {
        return agents(null);
    }

    @SuppressWarnings("unchecked")
    private Map<WorkflowNodeType, Agent> agents(AiClientRuntimeService runtime) throws Exception {
        LegalOrchestratorService service = new LegalOrchestratorService(runtime, null, null);
        Method method = LegalOrchestratorService.class.getDeclaredMethod("buildAgents", String.class, String.class, String.class);
        method.setAccessible(true);
        return (Map<WorkflowNodeType, Agent>) method.invoke(service, "test", null, null);
    }

    private AgentContext context(Map<String, Object> variables) {
        return new AgentContext("test", "run", "test", "问题", "问题", List.of(), Map.of(), variables, LocalDateTime.now());
    }

    private Map<String, Object> variables(String evidenceId, String quote) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("retrievalSafeForReasoning", true);
        variables.put("retrievedEvidences", List.of(new LegalEvidence("evidence-1", "当事人应当按照约定全面履行义务。",
                "document-1", "示例法规", "示例法规", "第一条", "", 0, "/sample/source", 0.9,
                Map.of(LegalKnowledgeMetadata.DOCUMENT_VERSION, "v1",
                        LegalKnowledgeMetadata.SOURCE_CHECKSUM, "checksum-1"))));
        variables.put("reasoningClaims", List.of(Map.of("text", "候选法律结论", "evidenceId", evidenceId, "quote", quote)));
        return variables;
    }
}
