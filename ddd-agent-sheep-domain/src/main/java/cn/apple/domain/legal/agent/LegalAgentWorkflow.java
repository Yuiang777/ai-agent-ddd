package cn.apple.domain.legal.agent;

import java.util.List;

public record LegalAgentWorkflow(String clientId, List<LegalAgentStep> steps) {
}
