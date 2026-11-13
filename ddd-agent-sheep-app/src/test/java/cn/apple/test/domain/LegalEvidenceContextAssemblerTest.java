package cn.apple.test.domain;

import cn.apple.domain.legal.model.valobj.LegalContextAssembly;
import cn.apple.domain.legal.model.valobj.LegalEvidence;
import cn.apple.domain.legal.service.LegalEvidenceContextAssembler;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LegalEvidenceContextAssemblerTest {

    private final LegalEvidenceContextAssembler assembler = new LegalEvidenceContextAssembler();

    @Test
    public void shouldCoverDifferentDocumentsBeforeAddingSiblingEvidence() {
        List<LegalEvidence> candidates = List.of(
                evidence("a-1", "doc-a", "第一条", "第一条的高分证据。"),
                evidence("a-2", "doc-a", "第二条", "第二条的相邻证据。"),
                evidence("b-1", "doc-b", "第三条", "其他法律来源的证据。")
        );

        LegalContextAssembly result = assembler.assemble(candidates, 3000);

        assertEquals(List.of("a-1", "b-1", "a-2"),
                result.evidences().stream().map(LegalEvidence::id).toList());
        assertTrue(result.contextChars() <= 3000);
    }

    @Test
    public void shouldRemoveRepeatedOverlapWithinTheSameDocument() {
        String repeated = "用人单位应当依法保障劳动者享有完整合法权益。";
        List<LegalEvidence> candidates = List.of(
                evidence("a-1", "doc-a", "第一条", "《测试法》 第一条：" + repeated + "第一项内容。"),
                evidence("a-2", "doc-a", "第二条", "《测试法》 第二条：" + repeated + "第二项内容。")
        );

        LegalContextAssembly result = assembler.assemble(candidates, 3000);

        assertEquals(1, occurrences(result.context(), repeated));
        assertTrue(result.context().contains("第一项内容"));
        assertTrue(result.context().contains("第二项内容"));
    }

    @Test
    public void shouldCompressAtBoundariesAndStayWithinBudget() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            text.append("这是第").append(i).append("个需要保留边界的完整法律句子。");
        }

        LegalContextAssembly result = assembler.assemble(
                List.of(evidence("a-1", "doc-a", "第一条", text.toString())), 800);

        assertTrue(result.contextChars() <= 800);
        assertTrue(result.context().contains("[中间内容已压缩]"));
        assertTrue(result.context().contains("第100个需要保留边界"));
    }

    @Test
    public void shouldKeepQueryRelevantSentencesAndTheirNeighbors() {
        String text = "背景说明与争议无关。第一项程序要求。解除合同需要满足法定条件。"
                + "解除通知应当依法送达。第二项程序要求。其他无关附录内容。";

        LegalContextAssembly result = assembler.assemble(
                List.of(evidence("a-1", "doc-a", "第一条", text)), "解除合同", 2000);

        assertTrue(result.context().contains("解除合同需要满足法定条件"));
        assertTrue(result.context().contains("解除通知应当依法送达"));
        assertTrue(result.context().contains("[低相关内容已省略]"));
        assertTrue(!result.context().contains("背景说明与争议无关"));
    }

    private LegalEvidence evidence(String id, String docId, String articleNo, String text) {
        return new LegalEvidence(
                id,
                text,
                docId,
                docId + ".md",
                "测试法",
                articleNo,
                "",
                1,
                "/laws/" + docId + ".md",
                0.9,
                Map.of("parent_ref", "第一章/" + articleNo));
    }

    private int occurrences(String text, String target) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(target, offset)) >= 0) {
            count++;
            offset += target.length();
        }
        return count;
    }
}
