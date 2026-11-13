package cn.apple.test.domain;

import cn.apple.domain.legal.model.entity.LegalDocument;
import cn.apple.domain.legal.model.valobj.LegalCorpusType;
import cn.apple.domain.legal.model.valobj.LegalDocumentChunk;
import cn.apple.domain.legal.model.valobj.LegalDocumentScope;
import cn.apple.domain.legal.model.valobj.LegalDocumentSource;
import cn.apple.domain.legal.model.valobj.LegalKnowledgeMetadata;
import cn.apple.domain.legal.service.LegalDocumentChunkService;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LegalDocumentChunkServiceTest {

    private final LegalDocumentChunkService service = new LegalDocumentChunkService();

    @Test
    public void shouldRespectArticleLinesAndNaturalParagraphs() {
        LegalDocument document = document("""
                中华人民共和国测试法

                目　　录
                第一章　总则

                第一章　总则

                第三十一条　人民法院应当依法指定监护人。

                依据本条第一款规定指定监护人前，应当先行保护被监护人的合法权益。

                第三十二条　没有合适人选的，由民政部门依法处理。
                """);

        List<LegalDocumentChunk> chunks = service.split(document);

        assertEquals(3, chunks.size());
        assertEquals("第三十一条", chunks.get(0).articleNo());
        assertEquals("第一款", chunks.get(0).clauseNo());
        assertEquals("第二款", chunks.get(1).clauseNo());
        assertTrue(chunks.get(1).text().contains("本条第一款规定"));
        assertEquals("第三十二条", chunks.get(2).articleNo());
        assertEquals("", chunks.get(2).clauseNo());
        assertFalse(chunks.get(0).text().contains("目　　录"));
        assertFalse(chunks.get(0).text().contains("第三十一条 第三十一条"));
        assertTrue(chunks.get(0).parentRef().contains("第一章 总则/第三十一条/第一款"));
    }

    @Test
    public void shouldCreateItemChunksWithTheirLeadInContext() {
        LegalDocument document = document("""
                第一章　监护

                第二十八条　由下列有监护能力的人按顺序担任监护人：

                （一）配偶；

                （二）父母、子女。
                """);

        List<LegalDocumentChunk> chunks = service.split(document);

        assertEquals(2, chunks.size());
        assertEquals("第一项", chunks.get(0).clauseNo());
        assertEquals("第二项", chunks.get(1).clauseNo());
        assertTrue(chunks.get(0).text().contains("由下列有监护能力的人"));
        assertTrue(chunks.get(0).text().contains("（一）配偶"));
        assertTrue(chunks.get(1).text().contains("（二）父母、子女"));
    }

    @Test
    public void shouldSplitLongArticlesAtSentenceBoundaries() {
        StringBuilder content = new StringBuilder("第1条　");
        for (int i = 1; i <= 80; i++) {
            content.append("完整句子").append(i).append("用于验证法律文本不会从半句话中间断开。");
        }

        List<LegalDocumentChunk> chunks = service.split(document(content.toString()));

        assertTrue(chunks.size() > 1);
        for (LegalDocumentChunk chunk : chunks) {
            assertTrue(chunk.text().length() <= 1200);
            String body = chunk.text().substring(chunk.text().indexOf('：') + 1);
            assertTrue(body.startsWith("完整句子"));
            assertTrue(body.endsWith("。"));
        }
    }

    @Test
    public void shouldIncludeChunkSchemaInDocumentVersion() {
        List<LegalDocumentChunk> chunks = service.split(document("第一条 依法履行合同。"));

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).chunkSchemaVersion().startsWith(
                LegalKnowledgeMetadata.LEGAL_CHUNK_SCHEMA_VERSION));
        assertEquals(32, chunks.get(0).documentVersion().length());
        assertFalse(chunks.get(0).documentVersion().equals(chunks.get(0).sourceChecksum().substring(0, 32)));
    }

    @Test
    public void shouldCleanPageNoiseAndExtractEffectiveDate() {
        LegalDocument document = document("\uFEFFLEGAL EXPORT\n施行日期：2024年1月2日\n"
                + "第一条 第一段包含\u200B不可见字符。\nCONFIDENTIAL FOOTER\n1\f"
                + "LEGAL EXPORT\n第二条 第二段包含" + (char) 0 + "控制字符。\nCONFIDENTIAL FOOTER\n2");

        List<LegalDocumentChunk> chunks = service.split(document);

        assertEquals(2, chunks.size());
        assertTrue(chunks.stream().allMatch(chunk -> "2024-01-02".equals(chunk.effectiveDate())));
        String text = chunks.stream().map(LegalDocumentChunk::text).reduce("", String::concat);
        assertFalse(text.contains("LEGAL EXPORT"));
        assertFalse(text.contains("CONFIDENTIAL FOOTER"));
        assertFalse(text.contains("\u200B"));
        assertFalse(text.contains(String.valueOf((char) 0)));
    }

    @Test
    public void shouldIncludeChunkSettingsInVersionFingerprint() {
        LegalDocument document = document("第一条 应当依法履行合同。");

        LegalDocumentChunk compact = new LegalDocumentChunkService(500, 50).split(document).get(0);
        LegalDocumentChunk broad = new LegalDocumentChunkService(700, 50).split(document).get(0);

        assertFalse(compact.chunkSchemaVersion().equals(broad.chunkSchemaVersion()));
        assertFalse(compact.documentVersion().equals(broad.documentVersion()));
    }

    @Test
    public void shouldSplitJudgmentsByCaseSectionsWithoutFakeArticleMetadata() {
        LegalDocument document = scopedDocument(
                "（2026）京01民终100号民事判决书.md",
                new LegalDocumentScope(LegalCorpusType.PUBLIC_CASE, null, null, null),
                "不应写入的法律名称",
                """
                        北京市第一中级人民法院民事判决书
                        （2026）京01民终100号

                        审理经过
                        本院依法组成合议庭公开审理了本案。

                        诉辩主张：上诉人请求解除合同并赔偿损失。

                        本院查明
                        双方于2025年签订服务合同。

                        本院认为
                        解除权行使应当符合法定条件。

                        判决如下：
                        一、驳回上诉，维持原判。
                        """);

        List<LegalDocumentChunk> chunks = service.split(document);

        assertEquals(List.of("文书首部", "审理经过", "诉辩主张", "本院查明", "本院认为", "判决如下"),
                chunks.stream().map(LegalDocumentChunk::parentRef).toList());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.lawName().isBlank()));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.articleNo().isBlank()));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.clauseNo().isBlank()));
        assertTrue(chunks.stream()
                .filter(chunk -> "本院认为".equals(chunk.parentRef()))
                .allMatch(chunk -> chunk.text().contains("解除权行使应当符合法定条件")));
    }

    @Test
    public void shouldPreserveHeadingHierarchyForPrivateMatterDocuments() {
        LegalDocument document = scopedDocument(
                "案件代理方案.md",
                new LegalDocumentScope(LegalCorpusType.MATTER_PRIVATE,
                        "tenant-001", "matter-001", "lawyer-001"),
                "",
                """
                        案件代理方案

                        # 事实梳理
                        委托人已经提供合同和付款凭证。

                        ## 争议焦点
                        对方是否构成根本违约。

                        第二条 证据保全
                        应当在开庭前申请保全服务器日志。

                        三、庭审策略
                        先证明合同义务，再证明催告和损失。
                        """);

        List<LegalDocumentChunk> chunks = service.split(document);

        assertTrue(chunks.stream().anyMatch(chunk -> "正文".equals(chunk.parentRef())));
        assertTrue(chunks.stream().anyMatch(chunk -> "事实梳理/争议焦点".equals(chunk.parentRef())));
        assertTrue(chunks.stream().anyMatch(chunk -> "事实梳理/争议焦点/第二条".equals(chunk.parentRef())));
        assertTrue(chunks.stream().anyMatch(chunk -> "三、庭审策略".equals(chunk.parentRef())));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.text().length() <= 1200));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.lawName().isBlank()));
    }

    @Test
    public void shouldIncludeCorpusTypeInVersionFingerprint() {
        String content = "第一条 相同正文在不同语料库中采用不同切片策略。";
        LegalDocumentChunk law = service.split(document(content)).get(0);
        LegalDocumentChunk legalCase = service.split(scopedDocument(
                "case.md",
                new LegalDocumentScope(LegalCorpusType.PUBLIC_CASE, null, null, null),
                "",
                content)).get(0);

        assertTrue(law.chunkSchemaVersion().contains("cpublic_law"));
        assertTrue(legalCase.chunkSchemaVersion().contains("cpublic_case"));
        assertFalse(law.chunkSchemaVersion().equals(legalCase.chunkSchemaVersion()));
        assertFalse(law.documentVersion().equals(legalCase.documentVersion()));
    }

    @Test
    public void shouldKeepSkippedLevelHeadingsAsSiblings() {
        LegalDocument document = scopedDocument(
                "研究笔记.md",
                new LegalDocumentScope(LegalCorpusType.FIRM_KNOWLEDGE, "tenant-001", null, null),
                "",
                """
                        ### 请求权基础
                        分析合同履行请求权。

                        ### 抗辩事由
                        分析时效和履行不能抗辩。
                        """);

        List<LegalDocumentChunk> chunks = service.split(document);

        assertEquals(List.of("请求权基础", "抗辩事由"),
                chunks.stream().map(LegalDocumentChunk::parentRef).toList());
    }

    private LegalDocument document(String content) {
        return new LegalDocument(
                new LegalDocumentSource("test-law.md", "test-law.md", Path.of("data/legal/raw/test-law.md")),
                "中华人民共和国测试法",
                content);
    }

    private LegalDocument scopedDocument(String docName,
                                         LegalDocumentScope scope,
                                         String lawName,
                                         String content) {
        return new LegalDocument(
                new LegalDocumentSource(docName, docName, Path.of("data/legal/raw").resolve(docName), scope, null),
                lawName,
                content);
    }
}
