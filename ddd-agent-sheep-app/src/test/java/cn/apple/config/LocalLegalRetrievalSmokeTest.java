package cn.apple.config;

import cn.apple.application.workflow.LegalClaimCitationVerifier;
import cn.apple.domain.legal.model.valobj.LegalKnowledgeMetadata;
import cn.apple.domain.legal.model.valobj.LegalRetrievalRequest;
import cn.apple.domain.legal.model.valobj.LegalRetrievalStatus;
import cn.apple.domain.legal.service.LegalEvidenceContextAssembler;
import cn.apple.domain.legal.service.LegalHybridRetrievalService;
import cn.apple.domain.legal.service.LegalScopeRouter;
import cn.apple.infrastructure.legal.LegalEvidenceRepository;
import org.junit.Test;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class LocalLegalRetrievalSmokeTest {

    @Test(timeout = 180000)
    public void shouldRetrieveActivePublicArticleThroughRealHybridPipeline() {
        assumeTrue("explicit local smoke opt-in required",
                "true".equals(System.getenv("RUN_LOCAL_LEGAL_RETRIEVAL_SMOKE")));
        String password = System.getenv("PGVECTOR_PASSWORD");
        assertNotNull("PGVECTOR_PASSWORD must be provided in the process environment", password);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:15432/springai?connectTimeout=5&socketTimeout=30"
                        + "&options=-c%20default_transaction_read_only%3Don",
                "postgres", password);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals("on", jdbc.queryForObject("show transaction_read_only", String.class));
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl("http://127.0.0.1:11434")
                .apiKey("local-only-placeholder").build();
        OpenAiEmbeddingModel model = new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                new AiAgentConfig().embeddingOptions("qwen3-embedding:4b", 1536));
        PgVectorStore store = PgVectorStore.builder(jdbc, model)
                .vectorTableName(LegalKnowledgeMetadata.DEFAULT_VECTOR_TABLE)
                .dimensions(1536).initializeSchema(false).build();
        LegalEvidenceRepository repository = new LegalEvidenceRepository(
                store, jdbc, LegalKnowledgeMetadata.DEFAULT_VECTOR_TABLE);
        LegalHybridRetrievalService service = new LegalHybridRetrievalService(
                repository, new LegalScopeRouter(), new LegalEvidenceContextAssembler());
        LegalRetrievalRequest request = new LegalRetrievalRequest(
                "开展数据处理活动，应当怎样建立数据安全管理制度并开展教育培训？",
                "law_name == '中华人民共和国数据安全法_20210610' && article_no == '第二十七条'",
                5, 10, 10);

        var result = service.retrieveWithDiagnostics(request);

        assertTrue("real vector search must be available", result.diagnostics().vector().available());
        assertTrue("real keyword search must be available", result.diagnostics().keyword().available());
        assertTrue(result.diagnostics().vector().resultCount() > 0);
        assertTrue(result.diagnostics().keyword().resultCount() > 0);
        assertFalse(result.evidences().isEmpty());
        assertFalse(result.context().isBlank());
        assertTrue(result.context().length() <= LegalEvidenceContextAssembler.DEFAULT_CONTEXT_BUDGET);
        assertEquals(result.evidences().size(), result.diagnostics().evidenceCount());
        for (var evidence : result.evidences()) {
            assertEquals("第二十七条", evidence.articleNo());
            request.metadataFilters().forEach((key, value) -> assertEquals(value, evidence.metadata().get(key)));
            assertNotNull(evidence.metadata().get(LegalKnowledgeMetadata.DOCUMENT_VERSION));
            assertNotNull(evidence.metadata().get(LegalKnowledgeMetadata.SOURCE_CHECKSUM));
        }

        var firstEvidence = result.evidences().get(0);
        String excerpt = firstEvidence.text().substring(0, Math.min(80, firstEvidence.text().length()));
        LegalClaimCitationVerifier verifier = new LegalClaimCitationVerifier();
        var matched = verifier.verify(List.of(new LegalClaimCitationVerifier.Claim(
                "合成核验正例，仅检查检索摘录与来源关联", "E1", excerpt)), result.evidences());
        assertTrue("retrieved original quotation must retain verifiable source metadata", matched.traceable());
        assertEquals("SOURCE_MATCHED", matched.findings().get(0).status());
        assertEquals(firstEvidence.docId(), matched.findings().get(0).documentId());

        String inventedExcerpt = excerpt + "这是仅用于负例测试的伪造追加文字，不是法规原文";
        assertFalse(firstEvidence.text().replaceAll("(?U)\\s+", "")
                .contains(inventedExcerpt.replaceAll("(?U)\\s+", "")));
        var rejectedQuote = verifier.verify(List.of(new LegalClaimCitationVerifier.Claim(
                "合成错误摘录负例", "E1", inventedExcerpt)), result.evidences());
        assertFalse(rejectedQuote.traceable());
        assertEquals("QUOTE_NOT_IN_SOURCE", rejectedQuote.findings().get(0).status());

        var rejectedReference = verifier.verify(List.of(new LegalClaimCitationVerifier.Claim(
                "合成未知证据编号负例", "E" + (result.evidences().size() + 1), excerpt)), result.evidences());
        assertFalse(rejectedReference.traceable());
        assertEquals("EVIDENCE_ID_UNKNOWN", rejectedReference.findings().get(0).status());

        LegalRetrievalRequest absentArticle = new LegalRetrievalRequest(
                request.query(),
                "law_name == '中华人民共和国数据安全法_20210610' && article_no == '第一千条'",
                5, 10, 10);
        var absentResult = service.retrieveWithDiagnostics(absentArticle);
        assertTrue("empty vector results must not be reported as a failed channel",
                absentResult.diagnostics().vector().available());
        assertTrue("empty keyword results must not be reported as a failed channel",
                absentResult.diagnostics().keyword().available());
        assertEquals(0, absentResult.diagnostics().vector().resultCount());
        assertEquals(0, absentResult.diagnostics().keyword().resultCount());
        assertTrue("unmatched article filter must not fall back to a different article",
                absentResult.evidences().isEmpty());
        assertTrue(absentResult.context().isBlank());
        assertEquals(LegalRetrievalStatus.INSUFFICIENT, absentResult.diagnostics().status());
        assertFalse(absentResult.safeForReasoning());
    }
}
