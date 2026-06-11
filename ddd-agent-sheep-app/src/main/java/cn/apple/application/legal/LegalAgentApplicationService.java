package cn.apple.application.legal;

import cn.apple.domain.legal.agent.LegalAgentWorkflow;
import cn.apple.domain.legal.agent.LegalAgentWorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LegalAgentApplicationService {

    private final LegalAgentWorkflowService legalAgentWorkflowService;

    public LegalAgentWorkflow workflow() {
        return legalAgentWorkflowService.buildDefaultWorkflow();
    }
}

