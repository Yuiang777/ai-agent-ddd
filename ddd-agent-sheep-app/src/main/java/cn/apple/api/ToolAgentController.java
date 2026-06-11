package cn.apple.api;

import cn.apple.application.agent.ToolAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class ToolAgentController {

    private final ToolAgentService toolAgentService;

    @PostMapping("/tool-call")
    public ApiResponse<ToolAgentService.ToolAgentResponse> toolCall(@RequestBody ToolAgentService.ToolAgentRequest request) {
        try {
            return ApiResponse.ok(toolAgentService.execute(request));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
