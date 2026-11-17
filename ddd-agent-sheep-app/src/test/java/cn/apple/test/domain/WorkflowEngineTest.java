package cn.apple.test.domain;

import cn.apple.domain.workflow.agent.Agent;
import cn.apple.domain.workflow.model.AgentContext;
import cn.apple.domain.workflow.model.AgentResult;
import cn.apple.domain.workflow.model.Citation;
import cn.apple.domain.workflow.model.RiskItem;
import cn.apple.domain.workflow.model.RiskLevel;
import cn.apple.domain.workflow.model.WorkflowDefinition;
import cn.apple.domain.workflow.model.WorkflowEdge;
import cn.apple.domain.workflow.model.WorkflowEvent;
import cn.apple.domain.workflow.model.WorkflowEventType;
import cn.apple.domain.workflow.model.WorkflowNode;
import cn.apple.domain.workflow.model.WorkflowNodeType;
import cn.apple.domain.workflow.model.WorkflowRunResult;
import cn.apple.domain.workflow.model.WorkflowStatus;
import cn.apple.domain.workflow.service.WorkflowEngine;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WorkflowEngineTest {

    private final WorkflowEngine engine = new WorkflowEngine();

    @Test
    public void shouldCompleteWhenEvidenceExistsAndRiskIsControlled() throws Exception {
        List<WorkflowEvent> events = new ArrayList<>();
        AgentResult agentResult = result(RiskLevel.LOW, true, Map.of());

        WorkflowRunResult result = engine.run(definition(), context(), Map.of(
                WorkflowNodeType.RISK, agent(agentResult)
        ), events::add);

        assertEquals(WorkflowStatus.SUCCESS, result.status());
        assertEquals("analysis", result.finalOutput());
        assertTrue(events.stream().noneMatch(e -> e.type() == WorkflowEventType.REVIEW_REQUIRED));
    }

    @Test
    public void shouldRequireReviewForHighRisk() throws Exception {
        List<WorkflowEvent> events = new ArrayList<>();

        WorkflowRunResult result = engine.run(definition(), context(), Map.of(
                WorkflowNodeType.RISK, agent(result(RiskLevel.HIGH, true, Map.of()))
        ), events::add);

        assertEquals(WorkflowStatus.REVIEW_REQUIRED, result.status());
        WorkflowEvent review = events.stream()
                .filter(e -> e.type() == WorkflowEventType.REVIEW_REQUIRED)
                .findFirst()
                .orElseThrow();
        assertTrue(((List<?>) review.data().get("reasons")).contains("存在高风险事项"));
    }

    @Test
    public void shouldRequireReviewWhenEvidenceIsMissing() throws Exception {
        WorkflowRunResult result = engine.run(definition(), context(), Map.of(
                WorkflowNodeType.RISK, agent(result(RiskLevel.LOW, false, Map.of()))
        ), ignored -> { });

        assertEquals(WorkflowStatus.REVIEW_REQUIRED, result.status());
    }

    @Test
    public void shouldPreserveSpecificReviewReasonsFromReviewAgent() throws Exception {
        WorkflowRunResult result = engine.run(definition(), context(), Map.of(
                WorkflowNodeType.RISK, agent(result(RiskLevel.LOW, true, Map.of(
                        "reviewRequired", true,
                        "reviewReasons", List.of("知识检索部分降级", "适用法域待核验")
                )))
        ), ignored -> { });

        assertEquals(WorkflowStatus.REVIEW_REQUIRED, result.status());
        assertTrue(((List<?>) result.variables().get("reviewReasons")).contains("知识检索部分降级"));
        assertTrue(((List<?>) result.variables().get("reviewReasons")).contains("适用法域待核验"));
    }

    @Test
    public void shouldForceReviewWhenStructuredModelFallsBack() throws Exception {
        WorkflowRunResult result = engine.run(definition(), context(), Map.of(
                WorkflowNodeType.RISK, agent(result(RiskLevel.LOW, true, Map.of(
                        "modelOutputReviewReasons", List.of("模型结构化输出兜底，必须人工复核")
                )))
        ), ignored -> { });

        assertEquals(WorkflowStatus.REVIEW_REQUIRED, result.status());
        assertTrue(((List<?>) result.variables().get("reviewReasons"))
                .contains("模型结构化输出兜底，必须人工复核"));
    }

    @Test
    public void shouldEmitNodeFailureBeforeRethrowing() {
        List<WorkflowEvent> events = new ArrayList<>();
        Agent brokenAgent = new Agent() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public AgentResult execute(AgentContext context) {
                throw new IllegalStateException("model unavailable");
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                engine.run(definition(), context(), Map.of(WorkflowNodeType.RISK, brokenAgent), events::add));

        assertEquals("model unavailable", error.getMessage());
        assertTrue(events.stream().anyMatch(e -> e.type() == WorkflowEventType.NODE_FAILED && "risk".equals(e.nodeId())));
        assertTrue(events.stream().anyMatch(e -> e.type() == WorkflowEventType.WORKFLOW_FAILED && "risk".equals(e.nodeId())));
    }

    @Test
    public void shouldEmitNodeFailureWhenAgentIsNotRegistered() {
        List<WorkflowEvent> events = new ArrayList<>();

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                engine.run(definition(), context(), Map.of(), events::add));

        assertTrue(error.getMessage().contains("agent not registered"));
        assertTrue(events.stream().anyMatch(e -> e.type() == WorkflowEventType.NODE_FAILED && "risk".equals(e.nodeId())));
    }

    @Test
    public void shouldSkipSubstantiveAgentsWhenIntakeBlocksWorkflow() throws Exception {
        AtomicBoolean strategyCalled = new AtomicBoolean(false);
        List<WorkflowEvent> events = new ArrayList<>();
        WorkflowDefinition definition = new WorkflowDefinition(
                "litigation", "Litigation", "start", "end",
                List.of(
                        new WorkflowNode("start", WorkflowNodeType.START, "Start", Map.of()),
                        new WorkflowNode("intake", WorkflowNodeType.INTAKE, "Intake", Map.of()),
                        new WorkflowNode("strategy", WorkflowNodeType.STRATEGY, "Strategy", Map.of()),
                        new WorkflowNode("end", WorkflowNodeType.END, "End", Map.of())
                ),
                List.of(
                        new WorkflowEdge("start", "intake", null),
                        new WorkflowEdge("intake", "strategy", null),
                        new WorkflowEdge("strategy", "end", null)
                ), Map.of());

        WorkflowRunResult result = engine.run(definition, context(), Map.of(
                WorkflowNodeType.INTAKE, agent(new AgentResult("受理暂停", List.of(), List.of(), Map.of(
                        "workflowBlocked", true,
                        "blockedBy", "intake",
                        "reviewReasons", List.of("利益冲突审查尚未完成")
                ))),
                WorkflowNodeType.STRATEGY, new Agent() {
                    @Override public String name() { return "strategy"; }
                    @Override public AgentResult execute(AgentContext context) {
                        strategyCalled.set(true);
                        return AgentResult.ofText("should not run");
                    }
                }
        ), events::add);

        assertEquals(WorkflowStatus.BLOCKED, result.status());
        assertEquals("受理暂停", result.finalOutput());
        assertTrue(!strategyCalled.get());
        assertTrue(events.stream().anyMatch(event -> event.type() == WorkflowEventType.NODE_SKIPPED && "strategy".equals(event.nodeId())));
    }

    @Test
    public void shouldPreserveLitigationArtifactsInResultAndCompletionEvent() throws Exception {
        List<WorkflowEvent> events = new ArrayList<>();
        WorkflowDefinition definition = new WorkflowDefinition(
                "litigation", "Litigation", "start", "end",
                List.of(
                        new WorkflowNode("start", WorkflowNodeType.START, "Start", Map.of()),
                        new WorkflowNode("issues", WorkflowNodeType.ISSUES, "Issues", Map.of()),
                        new WorkflowNode("opponent", WorkflowNodeType.OPPONENT, "Opponent", Map.of()),
                        new WorkflowNode("lawyer", WorkflowNodeType.LAWYER_REVIEW, "Lawyer", Map.of()),
                        new WorkflowNode("end", WorkflowNodeType.END, "End", Map.of())
                ),
                List.of(
                        new WorkflowEdge("start", "issues", null),
                        new WorkflowEdge("issues", "opponent", null),
                        new WorkflowEdge("opponent", "lawyer", null),
                        new WorkflowEdge("lawyer", "end", null)
                ), Map.of());

        WorkflowRunResult result = engine.run(definition, context(), Map.of(
                WorkflowNodeType.ISSUES, agent(new AgentResult("issues", List.of(), List.of(), Map.of("issuesMatrix", "{\"issues\":[]}"))),
                WorkflowNodeType.OPPONENT, agent(new AgentResult("opponent", List.of(), List.of(), Map.of("opponentAnalysis", "{\"arguments\":[]}"))),
                WorkflowNodeType.LAWYER_REVIEW, agent(new AgentResult("lawyer", List.of(), List.of(), Map.of(
                        "lawyerReviewStatus", "REQUIRED", "lawyerSignoffRequired", true, "reviewRequired", true,
                        "reviewReasons", List.of("诉讼案件必须由执业律师签发")
                )))
        ), events::add);

        assertEquals("{\"issues\":[]}", result.variables().get("issuesMatrix"));
        assertEquals("{\"arguments\":[]}", result.variables().get("opponentAnalysis"));
        assertEquals("REQUIRED", result.variables().get("lawyerReviewStatus"));
        WorkflowEvent completed = events.stream().filter(event -> event.type() == WorkflowEventType.WORKFLOW_COMPLETED).findFirst().orElseThrow();
        Map<?, ?> analysis = (Map<?, ?>) completed.data().get("litigationAnalysis");
        assertEquals("{\"issues\":[]}", analysis.get("issuesMatrix"));
        assertEquals("{\"arguments\":[]}", analysis.get("opponentAnalysis"));
        assertEquals("REQUIRED", analysis.get("lawyerReviewStatus"));
    }

    @Test
    public void shouldRejectUnreachableNodes() {
        WorkflowDefinition invalid = new WorkflowDefinition(
                "legal", "Legal", "start", "end",
                List.of(
                        new WorkflowNode("start", WorkflowNodeType.START, "Start", Map.of()),
                        new WorkflowNode("risk", WorkflowNodeType.RISK, "Risk", Map.of()),
                        new WorkflowNode("orphan", WorkflowNodeType.REPORT, "Orphan", Map.of()),
                        new WorkflowNode("end", WorkflowNodeType.END, "End", Map.of())
                ),
                List.of(new WorkflowEdge("start", "risk", null), new WorkflowEdge("risk", "end", null)),
                Map.of()
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> engine.validate(invalid));
        assertTrue(error.getMessage().contains("unreachable"));
    }

    private WorkflowDefinition definition() {
        return new WorkflowDefinition(
                "legal", "Legal", "start", "end",
                List.of(
                        new WorkflowNode("start", WorkflowNodeType.START, "Start", Map.of()),
                        new WorkflowNode("risk", WorkflowNodeType.RISK, "Risk", Map.of()),
                        new WorkflowNode("end", WorkflowNodeType.END, "End", Map.of())
                ),
                List.of(new WorkflowEdge("start", "risk", null), new WorkflowEdge("risk", "end", null)),
                Map.of()
        );
    }

    private AgentContext context() {
        return new AgentContext("legal", "run-1", "law-consultant", "question", "question",
                List.of(), Map.of(), Map.of(), LocalDateTime.now());
    }

    private Agent agent(AgentResult result) {
        return new Agent() {
            @Override
            public String name() {
                return "risk";
            }

            @Override
            public AgentResult execute(AgentContext context) {
                return result;
            }
        };
    }

    private AgentResult result(RiskLevel level, boolean withCitation, Map<String, Object> variables) {
        List<Citation> citations = withCitation
                ? List.of(new Citation("民法典", "official", "第五百零九条", "", "当事人应当按照约定全面履行义务"))
                : List.of();
        return new AgentResult("analysis", citations,
                List.of(new RiskItem("履约风险", level, "需要核实", "补充证据")), variables);
    }
}
