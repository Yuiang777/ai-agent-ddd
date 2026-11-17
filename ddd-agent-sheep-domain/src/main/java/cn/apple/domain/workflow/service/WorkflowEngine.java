package cn.apple.domain.workflow.service;

import cn.apple.domain.workflow.agent.Agent;
import cn.apple.domain.workflow.model.AgentContext;
import cn.apple.domain.workflow.model.AgentResult;
import cn.apple.domain.workflow.model.WorkflowDefinition;
import cn.apple.domain.workflow.model.WorkflowEdge;
import cn.apple.domain.workflow.model.WorkflowEvent;
import cn.apple.domain.workflow.model.WorkflowEventType;
import cn.apple.domain.workflow.model.WorkflowNode;
import cn.apple.domain.workflow.model.WorkflowNodeType;
import cn.apple.domain.workflow.model.WorkflowRunResult;
import cn.apple.domain.workflow.model.WorkflowStatus;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class WorkflowEngine {

    public WorkflowRunResult run(WorkflowDefinition definition,
                                 AgentContext baseContext,
                                 Map<WorkflowNodeType, Agent> agents,
                                 Consumer<WorkflowEvent> eventSink) throws Exception {
        validate(definition);

        LocalDateTime startedAt = LocalDateTime.now();
        long startMs = System.currentTimeMillis();
        String runId = baseContext.runId() == null || baseContext.runId().isBlank()
                ? UUID.randomUUID().toString().replace("-", "")
                : baseContext.runId();

        Map<String, WorkflowNode> nodeMap = definition.nodes().stream().collect(Collectors.toMap(WorkflowNode::nodeId, n -> n));
        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (WorkflowNode node : definition.nodes()) {
            outgoing.put(node.nodeId(), new ArrayList<>());
            indegree.put(node.nodeId(), 0);
        }
        for (WorkflowEdge edge : definition.edges()) {
            outgoing.computeIfAbsent(edge.fromNodeId(), ignored -> new ArrayList<>()).add(edge.toNodeId());
            indegree.put(edge.toNodeId(), indegree.getOrDefault(edge.toNodeId(), 0) + 1);
        }

        eventSink.accept(new WorkflowEvent(WorkflowEventType.WORKFLOW_STARTED, definition.workflowId(), runId, null, null,
                "workflow started", Map.of("workflowName", definition.workflowName()), LocalDateTime.now()));

        ArrayDeque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }

        Map<String, Object> variables = new LinkedHashMap<>();
        if (baseContext.variables() != null) {
            variables.putAll(baseContext.variables());
        }
        String latestText = baseContext.inputText();
        List<cn.apple.domain.workflow.model.Citation> citations = new ArrayList<>();
        List<cn.apple.domain.workflow.model.RiskItem> risks = new ArrayList<>();

        while (!ready.isEmpty()) {
            String nodeId = ready.removeFirst();
            WorkflowNode node = nodeMap.get(nodeId);
            if (node == null) {
                continue;
            }

            boolean blocked = Boolean.parseBoolean(String.valueOf(variables.getOrDefault("workflowBlocked", false)));
            if (blocked && node.type() != WorkflowNodeType.END) {
                eventSink.accept(new WorkflowEvent(WorkflowEventType.NODE_SKIPPED, definition.workflowId(), runId,
                        node.nodeId(), node.type(), "受理门禁未通过，节点未执行", Map.of("blockedBy", variables.getOrDefault("blockedBy", "intake")), LocalDateTime.now()));
                for (String next : outgoing.getOrDefault(nodeId, List.of())) {
                    int nextDegree = indegree.getOrDefault(next, 0) - 1;
                    indegree.put(next, nextDegree);
                    if (nextDegree == 0) ready.add(next);
                }
                continue;
            }

            long nodeStartMs = System.currentTimeMillis();
            eventSink.accept(new WorkflowEvent(WorkflowEventType.NODE_STARTED, definition.workflowId(), runId, node.nodeId(), node.type(),
                    node.name(), Map.of("nodeConfig", node.config()), LocalDateTime.now()));

            if (node.type() == WorkflowNodeType.START || node.type() == WorkflowNodeType.END) {
                eventSink.accept(new WorkflowEvent(WorkflowEventType.NODE_COMPLETED, definition.workflowId(), runId, node.nodeId(), node.type(),
                        node.name(), Map.of("costMs", System.currentTimeMillis() - nodeStartMs), LocalDateTime.now()));
            } else {
                Agent agent = agents.get(node.type());
                if (agent == null) {
                    IllegalStateException error = new IllegalStateException("agent not registered for nodeType: " + node.type());
                    eventSink.accept(new WorkflowEvent(WorkflowEventType.NODE_FAILED, definition.workflowId(), runId,
                            node.nodeId(), node.type(), node.name(), Map.of(
                                    "error", error.getMessage(),
                                    "exception", error.getClass().getName(),
                                    "costMs", System.currentTimeMillis() - nodeStartMs
                            ), LocalDateTime.now()));
                    eventSink.accept(new WorkflowEvent(WorkflowEventType.WORKFLOW_FAILED, definition.workflowId(), runId,
                            node.nodeId(), node.type(), "workflow failed", Map.of(
                                    "error", error.getMessage(),
                                    "failedNode", node.nodeId()
                            ), LocalDateTime.now()));
                    throw error;
                }
                AgentContext context = new AgentContext(
                        definition.workflowId(),
                        runId,
                        baseContext.clientId(),
                        baseContext.userMessage(),
                        latestText,
                        baseContext.attachments(),
                        baseContext.memory(),
                        mergeVars(variables, node.config()),
                        baseContext.startTime()
                );
                AgentResult result;
                try {
                    result = agent.execute(context);
                } catch (Exception e) {
                    String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    eventSink.accept(new WorkflowEvent(WorkflowEventType.NODE_FAILED, definition.workflowId(), runId,
                            node.nodeId(), node.type(), node.name(), Map.of(
                                    "error", error,
                                    "exception", e.getClass().getName(),
                                    "costMs", System.currentTimeMillis() - nodeStartMs
                            ), LocalDateTime.now()));
                    eventSink.accept(new WorkflowEvent(WorkflowEventType.WORKFLOW_FAILED, definition.workflowId(), runId,
                            node.nodeId(), node.type(), "workflow failed", Map.of(
                                    "error", error,
                                    "failedNode", node.nodeId()
                            ), LocalDateTime.now()));
                    throw e;
                }
                if (result != null) {
                    if (result.outputText() != null && !result.outputText().isBlank()) {
                        latestText = result.outputText();
                        eventSink.accept(new WorkflowEvent(WorkflowEventType.NODE_OUTPUT, definition.workflowId(), runId, node.nodeId(), node.type(),
                                "output", Map.of("text", latestText), LocalDateTime.now()));
                    }
                    if (result.variables() != null && !result.variables().isEmpty()) {
                        variables.putAll(result.variables());
                    }
                    if (result.citations() != null && !result.citations().isEmpty()) {
                        citations.addAll(result.citations());
                    }
                    if (result.risks() != null && !result.risks().isEmpty()) {
                        risks.addAll(result.risks());
                    }
                }

                eventSink.accept(new WorkflowEvent(WorkflowEventType.NODE_COMPLETED, definition.workflowId(), runId, node.nodeId(), node.type(),
                        node.name(), nodeCompletedData(node, result, System.currentTimeMillis() - nodeStartMs), LocalDateTime.now()));
            }

            for (String next : outgoing.getOrDefault(nodeId, List.of())) {
                int nextDegree = indegree.getOrDefault(next, 0) - 1;
                indegree.put(next, nextDegree);
                if (nextDegree == 0) {
                    ready.add(next);
                }
            }
        }

        long costMs = System.currentTimeMillis() - startMs;
        List<String> reviewReasons = reviewReasons(citations, risks, variables);
        boolean workflowBlocked = Boolean.parseBoolean(String.valueOf(variables.getOrDefault("workflowBlocked", false)));
        WorkflowStatus status = workflowBlocked
                ? WorkflowStatus.BLOCKED
                : (reviewReasons.isEmpty() ? WorkflowStatus.SUCCESS : WorkflowStatus.REVIEW_REQUIRED);
        if (!workflowBlocked && !reviewReasons.isEmpty()) {
            eventSink.accept(new WorkflowEvent(WorkflowEventType.REVIEW_REQUIRED, definition.workflowId(), runId, null, null,
                    "需要人工复核", Map.of("reasons", reviewReasons, "riskCount", risks.size(), "citationCount", citations.size()), LocalDateTime.now()));
        }

        Map<String, Object> resultVariables = new LinkedHashMap<>(variables);
        resultVariables.put("reviewRequired", !reviewReasons.isEmpty());
        resultVariables.put("reviewReasons", reviewReasons);
        resultVariables.put("riskCount", risks.size());
        resultVariables.put("citationCount", citations.size());
        WorkflowRunResult result = new WorkflowRunResult(
                definition.workflowId(),
                runId,
                status,
                latestText,
                List.copyOf(citations),
                List.copyOf(risks),
                Map.copyOf(resultVariables),
                costMs,
                startedAt,
                LocalDateTime.now()
        );
        Map<String, Object> completedData = new LinkedHashMap<>();
        completedData.put("costMs", costMs);
        completedData.put("finalOutput", latestText);
        completedData.put("citations", citations.size());
        completedData.put("risks", risks.size());
        completedData.put("citationItems", List.copyOf(citations));
        completedData.put("riskItems", List.copyOf(risks));
        completedData.put("status", status.name());
        completedData.put("reviewReasons", reviewReasons);
        completedData.put("workflowBlocked", workflowBlocked);
        completedData.put("litigationAnalysis", litigationAnalysis(resultVariables));
        completedData.put("modelOutputQuality", modelOutputQuality(resultVariables));
        eventSink.accept(new WorkflowEvent(WorkflowEventType.WORKFLOW_COMPLETED, definition.workflowId(), runId, null, null,
                "workflow completed", Map.copyOf(completedData), LocalDateTime.now()));
        return result;
    }

    private List<String> reviewReasons(List<cn.apple.domain.workflow.model.Citation> citations,
                                       List<cn.apple.domain.workflow.model.RiskItem> risks,
                                       Map<String, Object> variables) {
        List<String> reasons = new ArrayList<>();
        if (variables != null) {
            addConfiguredReasons(reasons, variables.get("reviewReasons"));
            addConfiguredReasons(reasons, variables.get("retrievalReviewReasons"));
            addConfiguredReasons(reasons, variables.get("modelOutputReviewReasons"));
        }
        if (risks.stream().anyMatch(r -> r.level() == cn.apple.domain.workflow.model.RiskLevel.HIGH)) {
            reasons.add("存在高风险事项");
        }
        boolean intakeBlocked = variables != null && Boolean.parseBoolean(String.valueOf(variables.getOrDefault("workflowBlocked", false)));
        if (citations.isEmpty() && !intakeBlocked) {
            reasons.add("未检索到可核验的法条或案例引用");
        }
        if (variables != null && variables.get("ragError") != null) {
            reasons.add("法律知识检索异常，需要人工确认依据");
        }
        if (variables != null && Boolean.parseBoolean(String.valueOf(variables.getOrDefault("reviewRequired", false)))) {
            reasons.add("工作流配置要求人工复核");
        }
        return reasons.stream().filter(reason -> reason != null && !reason.isBlank()).distinct().toList();
    }

    private void addConfiguredReasons(List<String> target, Object value) {
        if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                if (item != null && !item.toString().isBlank()) {
                    target.add(item.toString().trim());
                }
            }
        } else if (value != null && !value.toString().isBlank()) {
            target.add(value.toString().trim());
        }
    }

    private Map<String, Object> nodeCompletedData(WorkflowNode node, AgentResult result, long costMs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("costMs", costMs);
        if (result == null || result.variables() == null) {
            return Map.copyOf(data);
        }
        if (node.type() == WorkflowNodeType.RETRIEVAL) {
            copyIfPresent(result.variables(), data, "retrievalStatus");
            copyIfPresent(result.variables(), data, "retrievalDiagnostics");
            copyIfPresent(result.variables(), data, "retrievalReviewReasons");
        }
        if (node.type() == WorkflowNodeType.INTAKE) {
            copyIfPresent(result.variables(), data, "intakeApproved");
            copyIfPresent(result.variables(), data, "intakeDecision");
            copyIfPresent(result.variables(), data, "workflowBlocked");
            copyIfPresent(result.variables(), data, "reviewReasons");
        }
        if (node.type() == WorkflowNodeType.FACTS) copyIfPresent(result.variables(), data, "factsTimeline");
        if (node.type() == WorkflowNodeType.ISSUES) copyIfPresent(result.variables(), data, "issuesMatrix");
        if (node.type() == WorkflowNodeType.EVIDENCE) copyIfPresent(result.variables(), data, "evidenceMatrix");
        if (node.type() == WorkflowNodeType.PROCEDURE) copyIfPresent(result.variables(), data, "proceduralAnalysis");
        if (node.type() == WorkflowNodeType.OPPONENT) copyIfPresent(result.variables(), data, "opponentAnalysis");
        if (node.type() == WorkflowNodeType.STRATEGY) copyIfPresent(result.variables(), data, "litigationStrategy");
        if (node.type() == WorkflowNodeType.LAWYER_REVIEW) {
            copyIfPresent(result.variables(), data, "lawyerReviewStatus");
            copyIfPresent(result.variables(), data, "reviewRequired");
            copyIfPresent(result.variables(), data, "reviewReasons");
        }
        if (node.type() == WorkflowNodeType.REVIEW) {
            copyIfPresent(result.variables(), data, "reviewDecision");
            copyIfPresent(result.variables(), data, "reviewRequired");
            copyIfPresent(result.variables(), data, "reviewReasons");
        }
        return Map.copyOf(data);
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private Map<String, Object> mergeVars(Map<String, Object> base, Map<String, Object> delta) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (base != null) {
            merged.putAll(base);
        }
        if (delta != null) {
            merged.putAll(delta);
        }
        return merged;
    }

    private Map<String, Object> litigationAnalysis(Map<String, Object> variables) {
        Map<String, Object> analysis = new LinkedHashMap<>();
        for (String key : List.of("casePosture", "partyRole", "proceedingType", "proceedingStage",
                "representedPartyName", "opponentNames", "courtOrTribunal", "caseNumber", "nextDeadline",
                "matterReference", "conflictCheckReference", "engagementReference", "deadlineSource",
                "intakeApproved", "intakeDecision", "factsTimeline", "issuesMatrix", "evidenceMatrix",
                "proceduralAnalysis", "opponentAnalysis", "litigationStrategy", "lawyerReviewStatus",
                "lawyerSignoffRequired", "reportIssuable")) {
            copyIfPresent(variables, analysis, key);
        }
        return Map.copyOf(analysis);
    }

    private Map<String, Map<String, Object>> modelOutputQuality(Map<String, Object> variables) {
        Map<String, Map<String, Object>> quality = new LinkedHashMap<>();
        if (variables == null) {
            return quality;
        }
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.endsWith("ModelOutputValid")) {
                continue;
            }
            String artifact = key.substring(0, key.length() - "ModelOutputValid".length());
            Map<String, Object> detail = quality.computeIfAbsent(artifact, ignored -> new LinkedHashMap<>());
            detail.put("valid", entry.getValue());
            detail.put("repaired", variables.get(artifact + "ModelOutputRepaired"));
            detail.put("fallbackUsed", variables.get(artifact + "ModelOutputFallbackUsed"));
        }
        return quality;
    }

    public void validate(WorkflowDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("workflow definition is null");
        }
        if (definition.workflowId() == null || definition.workflowId().isBlank()) {
            throw new IllegalArgumentException("workflowId is blank");
        }
        if (definition.startNodeId() == null || definition.startNodeId().isBlank()) {
            throw new IllegalArgumentException("startNodeId is blank");
        }
        if (definition.endNodeId() == null || definition.endNodeId().isBlank()) {
            throw new IllegalArgumentException("endNodeId is blank");
        }
        if (definition.nodes() == null || definition.nodes().isEmpty()) {
            throw new IllegalArgumentException("workflow nodes are empty");
        }
        if (definition.edges() == null) {
            throw new IllegalArgumentException("workflow edges are null");
        }
        Map<String, WorkflowNode> nodeMap = new LinkedHashMap<>();
        for (WorkflowNode node : definition.nodes()) {
            if (node == null || node.nodeId() == null || node.nodeId().isBlank() || node.type() == null) {
                throw new IllegalArgumentException("workflow contains invalid node");
            }
            if (nodeMap.putIfAbsent(node.nodeId(), node) != null) {
                throw new IllegalArgumentException("duplicate nodeId: " + node.nodeId());
            }
        }
        if (!nodeMap.containsKey(definition.startNodeId())) {
            throw new IllegalArgumentException("start node not found: " + definition.startNodeId());
        }
        if (!nodeMap.containsKey(definition.endNodeId())) {
            throw new IllegalArgumentException("end node not found: " + definition.endNodeId());
        }
        if (nodeMap.get(definition.startNodeId()).type() != WorkflowNodeType.START) {
            throw new IllegalArgumentException("start node type must be START");
        }
        if (nodeMap.get(definition.endNodeId()).type() != WorkflowNodeType.END) {
            throw new IllegalArgumentException("end node type must be END");
        }
        for (WorkflowEdge edge : definition.edges()) {
            if (edge == null) {
                throw new IllegalArgumentException("workflow contains null edge");
            }
            if (!nodeMap.containsKey(edge.fromNodeId())) {
                throw new IllegalArgumentException("edge from node not found: " + edge.fromNodeId());
            }
            if (!nodeMap.containsKey(edge.toNodeId())) {
                throw new IllegalArgumentException("edge to node not found: " + edge.toNodeId());
            }
        }
        ensureAcyclic(definition);
        ensureConnected(definition, nodeMap);
    }

    private void ensureConnected(WorkflowDefinition definition, Map<String, WorkflowNode> nodeMap) {
        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (String nodeId : nodeMap.keySet()) {
            outgoing.put(nodeId, new ArrayList<>());
            indegree.put(nodeId, 0);
        }
        for (WorkflowEdge edge : definition.edges()) {
            outgoing.get(edge.fromNodeId()).add(edge.toNodeId());
            indegree.put(edge.toNodeId(), indegree.get(edge.toNodeId()) + 1);
        }
        if (indegree.get(definition.startNodeId()) != 0) {
            throw new IllegalArgumentException("start node must not have incoming edges");
        }
        if (!outgoing.get(definition.endNodeId()).isEmpty()) {
            throw new IllegalArgumentException("end node must not have outgoing edges");
        }

        Set<String> reachable = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(definition.startNodeId());
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!reachable.add(current)) {
                continue;
            }
            queue.addAll(outgoing.getOrDefault(current, List.of()));
        }
        if (!reachable.contains(definition.endNodeId())) {
            throw new IllegalArgumentException("end node is not reachable from start node");
        }
        if (reachable.size() != nodeMap.size()) {
            Set<String> disconnected = new HashSet<>(nodeMap.keySet());
            disconnected.removeAll(reachable);
            throw new IllegalArgumentException("workflow contains unreachable nodes: " + disconnected);
        }
    }

    private void ensureAcyclic(WorkflowDefinition definition) {
        Map<String, List<String>> outgoing = new HashMap<>();
        for (WorkflowNode node : definition.nodes()) {
            outgoing.put(node.nodeId(), new ArrayList<>());
        }
        for (WorkflowEdge edge : definition.edges()) {
            outgoing.computeIfAbsent(edge.fromNodeId(), ignored -> new ArrayList<>()).add(edge.toNodeId());
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (WorkflowNode node : definition.nodes()) {
            if (!visited.contains(node.nodeId())) {
                dfs(node.nodeId(), outgoing, visiting, visited);
            }
        }
    }

    private void dfs(String nodeId, Map<String, List<String>> outgoing, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(nodeId)) {
            throw new IllegalArgumentException("workflow has cycle at node: " + nodeId);
        }
        if (visited.contains(nodeId)) {
            return;
        }
        visiting.add(nodeId);
        for (String next : outgoing.getOrDefault(nodeId, List.of())) {
            dfs(next, outgoing, visiting, visited);
        }
        visiting.remove(nodeId);
        visited.add(nodeId);
    }
}
