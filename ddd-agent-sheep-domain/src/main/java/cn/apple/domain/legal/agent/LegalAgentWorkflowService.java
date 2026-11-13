package cn.apple.domain.legal.agent;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LegalAgentWorkflowService {

    public LegalAgentWorkflow buildDefaultWorkflow() {
        return new LegalAgentWorkflow("law-consultant", List.of(
                new LegalAgentStep(LegalAgentRole.INTAKE, "先完成委托授权、利益冲突、当事人和程序信息核验；门禁未通过时不读取材料、不生成实体策略。"),
                new LegalAgentStep(LegalAgentRole.FACTS, "将材料整理为事实时间线，严格区分已证实事实、客户陈述和待核验事实。"),
                new LegalAgentStep(LegalAgentRole.ISSUES, "按请求权或抗辩要件生成争点和举证责任矩阵。"),
                new LegalAgentStep(LegalAgentRole.RETRIEVAL, "使用租户和知识标签隔离的混合 RAG 检索候选法条、案例和版本化来源。"),
                new LegalAgentStep(LegalAgentRole.EVIDENCE, "核验每项证据的真实性、关联性、合法性，并记录合法保全和补强动作。"),
                new LegalAgentStep(LegalAgentRole.PROCEDURE, "核验管辖、阶段和期限；检索质量不足时不作程序实体结论。"),
                new LegalAgentStep(LegalAgentRole.REASONING, "只基于检索到的法条和已确认事实逐争点分析；不保证胜诉。"),
                new LegalAgentStep(LegalAgentRole.OPPONENT, "独立提出对方最强主张和可能证据，避免单方确认偏差。"),
                new LegalAgentStep(LegalAgentRole.STRATEGY, "给出主位、备选、抗辩或反请求、保全、和解与执行方案，仅限合法取证和程序行动。"),
                new LegalAgentStep(LegalAgentRole.REVIEW, "检查引用、证据质量、禁止行为和免责声明。"),
                new LegalAgentStep(LegalAgentRole.LAWYER_SIGNOFF, "诉讼结果始终作为内部工作底稿，须由执业律师独立核验后才可签发。")
        ));
    }
}
