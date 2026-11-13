package cn.apple.domain.legal.agent;

public enum LegalAgentRole {

    INTAKE("受理与冲突审查智能体", "核验委托授权、当事人身份、利益冲突与禁止行为，未通过时不读取案件材料"),
    FACTS("事实时间线智能体", "区分客户陈述、已证实事实和待核验事实，输出可追溯时间线"),
    ISSUES("争点与举证责任智能体", "按请求权或抗辩要件拆解争点、举证责任和待证明事实"),
    RETRIEVAL("检索智能体", "从法律知识库中定位候选法条和上下文"),
    EVIDENCE("证据审查智能体", "核验真实性、关联性、合法性，登记证据保全与补强缺口"),
    PROCEDURE("程序期限智能体", "核验管辖、程序阶段、诉讼时效和近期程序期限"),
    REASONING("推理智能体", "基于候选法条组织法律分析和回答要点"),
    OPPONENT("对方论证智能体", "审视对方最强主张、可能证据及其对本方方案的影响"),
    STRATEGY("诉讼策略智能体", "整合主位和备选方案、抗辩或反请求、保全、和解与执行安排"),
    REVIEW("审查智能体", "检查引用准确性、拒答边界和免责声明"),
    LAWYER_SIGNOFF("律师签发智能体", "确保诉讼工作底稿必须由执业律师独立核验并签发"),
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
