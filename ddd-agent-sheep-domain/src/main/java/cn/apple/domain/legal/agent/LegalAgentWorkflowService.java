package cn.apple.domain.legal.agent;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LegalAgentWorkflowService {

    public LegalAgentWorkflow buildDefaultWorkflow() {
        return new LegalAgentWorkflow("law-consultant", List.of(
                new LegalAgentStep(LegalAgentRole.RETRIEVAL, "使用 knowledge == 'law-consultant' 的 RAG 检索候选法条，优先保留 law_name、article_no、clause_no 元数据。"),
                new LegalAgentStep(LegalAgentRole.REASONING, "只基于检索到的法条组织结论；如果缺少依据，明确说明无法判断，不能编造法条。"),
                new LegalAgentStep(LegalAgentRole.REVIEW, "复核第三条第二款与第三条之二等相似条款，检查引用是否能追溯到 chunk 元数据。"),
                new LegalAgentStep(LegalAgentRole.INTERACTION, "输出面向用户的答复，包含法条引用、非正式法律意见免责声明，以及需要用户补充的事实。")
        ));
    }
}
