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

            eventSink.accept(new WorkflowEvent(WorkflowEventType.NODE_STARTED, definition.workflowId(), runId, node.nodeId(), node.type(),
                    node.name(), Map.of("nodeConfig", node.config()), LocalDateTime.now()));

            if (node.type() == WorkflowNodeType.START || node.type() == WorkflowNodeType.END) {
                eventSink.accept(new WorkflowEvent(WorkflowEventType.NODE_COMPLETED, definition.workflowId(), runId, node.nodeId(), node.type(),
                        node.name(), Map.of(), LocalDateTime.now()));
            } else {
                Agent agent = agents.get(node.type());
                if (agent == null) {
                    throw new IllegalStateException("agent not registered for nodeType: " + node.type());
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
                AgentResult result = agent.execute(context);
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
                        node.name(), Map.of(), LocalDateTime.now()));
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
        WorkflowRunResult result = new WorkflowRunResult(
                definition.workflowId(),
                runId,
                WorkflowStatus.SUCCESS,
                latestText,
                List.copyOf(citations),
                List.copyOf(risks),
                Map.copyOf(variables),
                costMs,
                startedAt,
                LocalDateTime.now()
        );
        eventSink.accept(new WorkflowEvent(WorkflowEventType.WORKFLOW_COMPLETED, definition.workflowId(), runId, null, null,
                "workflow completed", Map.of(
                        "costMs", costMs,
                        "finalOutput", latestText,
                        "citations", citations.size(),
                        "risks", risks.size()
                ), LocalDateTime.now()));
        return result;
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
        Map<String, WorkflowNode> nodeMap = definition.nodes().stream().collect(Collectors.toMap(WorkflowNode::nodeId, n -> n, (a, b) -> a));
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
            if (!nodeMap.containsKey(edge.fromNodeId())) {
                throw new IllegalArgumentException("edge from node not found: " + edge.fromNodeId());
            }
            if (!nodeMap.containsKey(edge.toNodeId())) {
                throw new IllegalArgumentException("edge to node not found: " + edge.toNodeId());
            }
        }
        ensureAcyclic(definition);
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
