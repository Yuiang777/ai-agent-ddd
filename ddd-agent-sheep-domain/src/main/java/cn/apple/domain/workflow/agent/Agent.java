package cn.apple.domain.workflow.agent;

import cn.apple.domain.workflow.model.AgentContext;
import cn.apple.domain.workflow.model.AgentResult;

public interface Agent {

    String name();

    AgentResult execute(AgentContext context) throws Exception;
}

