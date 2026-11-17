package cn.apple.api;

import cn.apple.application.legal.LegalAgentApplicationService;
import cn.apple.domain.legal.agent.LegalAgentWorkflow;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/legal/agent")
@RequiredArgsConstructor
public class LegalAgentController {

    private final LegalAgentApplicationService legalAgentApplicationService;

    @GetMapping("/workflow")
    public ApiResponse<LegalAgentWorkflow> workflow() {
        return ApiResponse.ok(legalAgentApplicationService.workflow());
    }
}
