package cn.apple.domain.legal.agent;

public enum LegalAgentRole {

    RETRIEVAL("检索智能体", "从法律知识库中定位候选法条和上下文"),
    REASONING("推理智能体", "基于候选法条组织法律分析和回答要点"),
    REVIEW("审查智能体", "检查引用准确性、拒答边界和免责声明"),
    INTERACTION("用户交互智能体", "整理最终回复并提示用户补充关键信息");

    private final String name;
    private final String responsibility;

    LegalAgentRole(String name, String responsibility) {
        this.name = name;
        this.responsibility = responsibility;
    }

    public String getName() {
        return name;
    }

    public String getResponsibility() {
        return responsibility;
    }
}
