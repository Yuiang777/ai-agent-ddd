package cn.apple.test.domain;

import cn.apple.domain.legal.adapter.repository.ILegalEvidenceRepository;
import cn.apple.domain.legal.model.valobj.LegalEvidence;
import cn.apple.domain.legal.model.valobj.LegalKnowledgeMetadata;
import cn.apple.domain.legal.model.valobj.LegalRetrievalRequest;
import cn.apple.domain.legal.model.valobj.LegalRetrievalResult;
import cn.apple.domain.legal.model.valobj.LegalRetrievalStatus;
import cn.apple.domain.legal.model.valobj.LegalScopeRoute;
import cn.apple.domain.legal.service.LegalEvidenceContextAssembler;
import cn.apple.domain.legal.service.LegalHybridRetrievalService;
import cn.apple.domain.legal.service.LegalScopeRouter;
import cn.apple.domain.legal.model.entity.LegalDocument;
import cn.apple.domain.legal.model.valobj.LegalDocumentChunk;
import cn.apple.domain.legal.model.valobj.LegalDocumentSource;
import cn.apple.domain.legal.model.valobj.LegalCorpusType;
import cn.apple.domain.legal.service.LegalDocumentChunkService;
import org.junit.Test;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class LegalHybridRetrievalServiceTest {

    private final LegalScopeRoute route = new LegalScopeRoute(
            "contract", "Contract", List.of("Civil Code"), List.of(), 0.9, "test route");

    @Test
    public void shouldMergeAndDeduplicateHybridResults() {
        LegalEvidence shared = evidence("shared", "doc-a", "Civil Code", 1, "Shared evidence");
        LegalEvidence vectorOnly = evidence("vector", "doc-b", "Civil Code", 1, "Vector evidence");
        LegalEvidence keywordOnly = evidence("keyword", "doc-c", "Civil Code", 1, "Keyword evidence");
        LegalHybridRetrievalService service = service(new FakeRepository(
                List.of(shared, vectorOnly), List.of(shared, keywordOnly), null, null));

        LegalRetrievalResult result = service.retrieveWithDiagnostics(request(6));

        assertEquals(LegalRetrievalStatus.HEALTHY, result.diagnostics().status());
        assertEquals(3, result.evidences().size());
        assertEquals("shared", result.evidences().get(0).id());
        assertTrue(result.safeForReasoning());
        assertTrue(result.context().contains("Shared evidence"));
    }

    @Test
    public void shouldDegradeWhenOneSourceFails() {
        LegalHybridRetrievalService service = service(new FakeRepository(
                List.of(evidence("vector", "doc-a", "Civil Code", 1, "Vector evidence")),
                List.of(), null, new IllegalStateException("keyword unavailable")));

        LegalRetrievalResult result = service.retrieveWithDiagnostics(request(6));

        assertEquals(LegalRetrievalStatus.DEGRADED, result.diagnostics().status());
        assertFalse(result.safeForReasoning());
        assertTrue(result.diagnostics().vector().available());
        assertFalse(result.diagnostics().keyword().available());
        assertTrue(result.diagnostics().reviewReasons().stream().anyMatch(reason -> reason.contains("降级")));
    }

    @Test
    public void shouldFailClosedWhenAllSourcesFail() {
        LegalHybridRetrievalService service = service(new FakeRepository(
                List.of(), List.of(), new IllegalStateException("vector unavailable"),
                new IllegalStateException("keyword unavailable")));

        LegalRetrievalResult result = service.retrieveWithDiagnostics(request(6));

        assertEquals(LegalRetrievalStatus.UNAVAILABLE, result.diagnostics().status());
        assertTrue(result.evidences().isEmpty());
        assertFalse(result.safeForReasoning());
        assertTrue(result.diagnostics().reviewReasons().size() >= 2);
    }

    @Test
    public void shouldEnforceContextBudgetAndDocumentDiversity() {
        LegalEvidenceContextAssembler assembler = new LegalEvidenceContextAssembler();
        List<LegalEvidence> candidates = List.of(
                evidence("a-1", "doc-a", "Civil Code", 1, "x".repeat(700)),
                evidence("a-2", "doc-a", "Civil Code", 2, "y".repeat(700)),
                evidence("a-3", "doc-a", "Civil Code", 3, "z".repeat(700)),
                evidence("b-1", "doc-b", "Labor Law", 1, "b".repeat(700))
        );

        var result = assembler.assemble(candidates, 1600);

        assertTrue(result.contextChars() <= 1600);
        assertTrue(result.evidences().stream().filter(e -> "doc-a".equals(e.docId())).count() <= 2);
        assertTrue(result.droppedEvidenceCount() > 0);
    }

    @Test
    public void shouldApplyKnowledgeAndTenantIsolationToVectorFilter() {
        LegalRetrievalRequest request = new LegalRetrievalRequest(
                "query", "status == 'effective'", 6, 0, 0, route, "legal-knowledge", "tenant-001");

        assertEquals("legal-knowledge", request.normalizedKnowledgeTag());
        assertEquals("tenant-001", request.normalizedTenantId());
        assertTrue(request.metadataFilterExpression().contains("knowledge == 'legal-knowledge'"));
        assertTrue(request.metadataFilterExpression().contains("corpus_type == 'firm_knowledge'"));
        assertTrue(request.metadataFilterExpression().contains("tenant_id == 'tenant-001'"));
        assertTrue(request.metadataFilterExpression().contains("ingestion_status == 'active'"));
        assertTrue(request.metadataFilterExpression().contains("status == 'effective'"));
        assertEquals(LegalKnowledgeMetadata.STATUS_ACTIVE,
                request.metadataFilters().get(LegalKnowledgeMetadata.INGESTION_STATUS));
        assertEquals(List.of(
                        LegalKnowledgeMetadata.KNOWLEDGE,
                        LegalKnowledgeMetadata.INGESTION_STATUS,
                        LegalKnowledgeMetadata.CORPUS_TYPE,
                        LegalKnowledgeMetadata.TENANT_ID,
                        "status"),
                new ArrayList<>(request.metadataFilters().keySet()));
    }

    @Test
    public void shouldProduceStableDocumentVersionMetadata() {
        LegalDocument document = new LegalDocument(
                new LegalDocumentSource("contract.txt", "contract.txt", Path.of("contract.txt")),
                "Civil Code",
                "Article 1 This is a clause.\nArticle 2 Another clause.");
        LegalDocumentChunkService chunkService = new LegalDocumentChunkService();

        List<LegalDocumentChunk> first = chunkService.split(document);
        List<LegalDocumentChunk> second = chunkService.split(document);

        assertFalse(first.isEmpty());
        assertEquals(first.get(0).sourceChecksum(), second.get(0).sourceChecksum());
        assertEquals(first.get(0).documentVersion(), second.get(0).documentVersion());
        assertTrue(first.get(0).chunkSchemaVersion().startsWith(
                LegalKnowledgeMetadata.LEGAL_CHUNK_SCHEMA_VERSION));
        assertEquals(first.get(0).parentRef(), second.get(0).parentRef());
    }

    @Test
    public void shouldRerankAnExactArticleReferenceAheadOfFusionOrder() {
        LegalEvidence distractor = evidence("article-41", "doc-a", "Civil Code", 41, "General rule");
        LegalEvidence exact = evidence("article-42", "doc-a", "Civil Code", 42, "Exact legal rule");
        LegalHybridRetrievalService service = service(new FakeRepository(
                List.of(distractor, exact), List.of(distractor, exact), null, null));
        LegalRetrievalRequest request = new LegalRetrievalRequest(
                "Civil Code Article 42", null, 2, 0, 0, route, "law-consultant", null);

        LegalRetrievalResult result = service.retrieveWithDiagnostics(request);

        assertEquals("article-42", result.evidences().get(0).id());
        assertEquals("hybrid-feature-v1", result.evidences().get(0).metadata().get(
                LegalKnowledgeMetadata.RERANK_STRATEGY));
    }

    @Test
    public void shouldMeasureExactArticleRankingAcrossAdjacentArticleCases() {
        int[][] articles = {{42, 41, 43}, {563, 562, 564}, {577, 576, 578}};
        int hitsAtThree = 0;
        double reciprocalRankSum = 0;
        for (int[] articleCase : articles) {
            LegalEvidence before = evidence("article-" + articleCase[1], "doc-a", "Civil Code", articleCase[1], "Distractor");
            LegalEvidence after = evidence("article-" + articleCase[2], "doc-a", "Civil Code", articleCase[2], "Distractor");
            LegalEvidence expected = evidence("article-" + articleCase[0], "doc-a", "Civil Code", articleCase[0], "Expected evidence");
            LegalHybridRetrievalService service = service(new FakeRepository(
                    List.of(before, after, expected), List.of(before, after, expected), null, null));
            LegalRetrievalResult result = service.retrieveWithDiagnostics(new LegalRetrievalRequest(
                    "Civil Code Article " + articleCase[0], null, 3, 0, 0, route, "law-consultant", null));
            int rank = 0;
            for (int index = 0; index < result.evidences().size(); index++) {
                if (expected.id().equals(result.evidences().get(index).id())) {
                    rank = index + 1;
                    break;
                }
            }
            if (rank > 0 && rank <= 3) {
                hitsAtThree++;
                reciprocalRankSum += 1.0 / rank;
            }
        }
        assertEquals("adjacent-article recall@3", articles.length, hitsAtThree);
        assertEquals("adjacent-article MRR@3", 1.0, reciprocalRankSum / articles.length, 0.0);
    }

    @Test
    public void shouldDegradeToFusionOrderWhenRerankerFails() {
        LegalEvidence evidence = evidence("article-1", "doc-a", "Civil Code", 1, "Legal rule");
        FakeRepository repository = new FakeRepository(List.of(evidence), List.of(evidence), null, null);
        LegalHybridRetrievalService service = new LegalHybridRetrievalService(
                repository,
                new LegalScopeRouter(),
                new LegalEvidenceContextAssembler(),
                (query, route, candidates, limit) -> {
                    throw new IllegalStateException("reranker unavailable");
                });

        LegalRetrievalResult result = service.retrieveWithDiagnostics(request(1));

        assertEquals(LegalRetrievalStatus.DEGRADED, result.diagnostics().status());
        assertEquals("fusion-fallback", result.evidences().get(0).metadata().get(
                LegalKnowledgeMetadata.RERANK_STRATEGY));
        assertTrue(result.diagnostics().reviewReasons().stream()
                .anyMatch(reason -> reason.contains("重排序")));
    }

    @Test
    public void shouldBoundRetrievalFanoutAndRejectUnsafeFilters() {
        LegalRetrievalRequest bounded = new LegalRetrievalRequest(
                "query", "status == 'effective'", 1000, 1000, 1000, route, "legal-knowledge", "tenant-001");
        assertEquals(50, bounded.normalizedTopK());
        assertEquals(200, bounded.normalizedVectorTopK());
        assertEquals(200, bounded.normalizedKeywordTopK());

        LegalRetrievalRequest unsafe = new LegalRetrievalRequest(
                "query", "status == 'effective'; drop", 6, 0, 0, route, "legal-knowledge", "tenant-001");
        org.junit.Assert.assertThrows(IllegalArgumentException.class, unsafe::metadataFilterExpression);

        LegalRetrievalRequest conflicting = new LegalRetrievalRequest(
                "query", "ingestion_status == 'staging'", 6, 0, 0, route, "legal-knowledge", "tenant-001");
        org.junit.Assert.assertThrows(IllegalArgumentException.class, conflicting::metadataFilters);

        LegalRetrievalRequest unsupported = new LegalRetrievalRequest(
                "query", "unsafe_key == 'value'", 6, 0, 0, route, "legal-knowledge", "tenant-001");
        org.junit.Assert.assertThrows(IllegalArgumentException.class, unsupported::metadataFilters);
    }

    @Test
    public void shouldRequireTenantAndMatterForPrivateCorpus() {
        LegalRetrievalRequest missingMatter = new LegalRetrievalRequest(
                "case facts", null, 6, 0, 0, route, "legal-knowledge",
                "tenant-001", LegalCorpusType.MATTER_PRIVATE, null);

        assertThrows(IllegalArgumentException.class, missingMatter::metadataFilters);

        LegalRetrievalRequest missingActor = new LegalRetrievalRequest(
                "case facts", null, 6, 0, 0, route, "legal-knowledge",
                "tenant-001", LegalCorpusType.MATTER_PRIVATE, "matter-009");
        assertThrows(IllegalArgumentException.class, missingActor::metadataFilters);

        LegalRetrievalRequest scoped = new LegalRetrievalRequest(
                "case facts", null, 6, 0, 0, route, "legal-knowledge",
                "tenant-001", LegalCorpusType.MATTER_PRIVATE, "matter-009", "lawyer-7");
        Map<String, String> filters = scoped.metadataFilters();

        assertEquals(LegalCorpusType.MATTER_PRIVATE.code(), filters.get(LegalKnowledgeMetadata.CORPUS_TYPE));
        assertEquals("tenant-001", filters.get(LegalKnowledgeMetadata.TENANT_ID));
        assertEquals("matter-009", filters.get(LegalKnowledgeMetadata.MATTER_ID));
        assertEquals("lawyer-7", filters.get(LegalKnowledgeMetadata.ACL_SUBJECT_ID));
    }

    @Test
    public void shouldRejectPrivateIdentifiersForPublicCorpus() {
        LegalRetrievalRequest request = new LegalRetrievalRequest(
                "law", null, 6, 0, 0, route, "legal-knowledge",
                "tenant-001", LegalCorpusType.PUBLIC_LAW, null);

        assertThrows(IllegalArgumentException.class, request::metadataFilters);
    }

    @Test
    public void shouldFederatePublicAndActorScopedMatterEvidence() {
        List<LegalRetrievalRequest> seen = new ArrayList<>();
        ILegalEvidenceRepository repository = new ILegalEvidenceRepository() {
            @Override
            public List<LegalEvidence> vectorSearch(LegalRetrievalRequest request) {
                seen.add(request);
                return List.of(scopedEvidence(request));
            }

            @Override
            public List<LegalEvidence> keywordSearch(LegalRetrievalRequest request) {
                return List.of(scopedEvidence(request));
            }

            private LegalEvidence scopedEvidence(LegalRetrievalRequest request) {
                boolean privateCorpus = request.corpusType() == LegalCorpusType.MATTER_PRIVATE;
                Map<String, Object> metadata = new java.util.LinkedHashMap<>(request.metadataFilters());
                metadata.put(LegalKnowledgeMetadata.SOURCE_CHECKSUM, "checksum");
                metadata.put(LegalKnowledgeMetadata.DOCUMENT_VERSION, "version");
                return new LegalEvidence(
                        privateCorpus ? "private-1" : "public-1",
                        privateCorpus ? "案件证据证明已经履行合同。" : "违约责任应当依法承担。",
                        privateCorpus ? "matter-doc" : "law-doc",
                        privateCorpus ? "证据.txt" : "民法典.txt",
                        privateCorpus ? "" : "民法典",
                        privateCorpus ? "" : "第五百七十七条",
                        "", 0, privateCorpus ? "s3://matter/evidence" : "/laws/civil-code.txt",
                        0.9, metadata);
            }
        };
        LegalHybridRetrievalService service = service(repository);
        String query = "合同是否已经履行";
        List<LegalRetrievalRequest> requests = List.of(
                new LegalRetrievalRequest(query, null, 6, 0, 0, route,
                        "law-consultant", null, LegalCorpusType.PUBLIC_LAW, null, null),
                new LegalRetrievalRequest(query, null, 6, 0, 0, route,
                        "law-consultant", "tenant-1", LegalCorpusType.MATTER_PRIVATE,
                        "matter-1", "lawyer-1"));

        LegalRetrievalResult result = service.retrieveFederated(requests);

        assertEquals(2, result.evidences().size());
        assertTrue(seen.stream().anyMatch(request ->
                "lawyer-1".equals(request.metadataFilters().get(LegalKnowledgeMetadata.ACL_SUBJECT_ID))));
        assertTrue(result.context().contains("案件证据"));
        assertTrue(result.context().contains("违约责任"));
    }

    @Test
    public void shouldSerializeAdvisorFilterAstIntoSupportedEqualitySyntax() {
        var expression = new FilterExpressionTextParser().parse(
                "knowledge == 'law-consultant' && status == 'effective'");

        String serialized = LegalRetrievalRequest.serializeSupportedFilter(expression);
        LegalRetrievalRequest request = new LegalRetrievalRequest(
                "query", serialized, 6, 0, 0, route, "law-consultant", null);

        assertEquals("law-consultant", request.metadataFilters().get(LegalKnowledgeMetadata.KNOWLEDGE));
        assertEquals("effective", request.metadataFilters().get("status"));
    }

    private LegalHybridRetrievalService service(ILegalEvidenceRepository repository) {
        return new LegalHybridRetrievalService(repository, new LegalScopeRouter(), new LegalEvidenceContextAssembler());
    }

    private LegalRetrievalRequest request(int topK) {
        return new LegalRetrievalRequest("contract breach", null, topK, 0, 0, route, "law-consultant", null);
    }

    private LegalEvidence evidence(String id, String docId, String lawName, int chunkIndex, String text) {
        return new LegalEvidence(id, text, docId, docId + ".txt", lawName,
                "Article " + chunkIndex, "", chunkIndex, "/laws/" + docId + ".txt", 0.9, Map.of());
    }

    private record FakeRepository(List<LegalEvidence> vector,
                                  List<LegalEvidence> keyword,
                                  RuntimeException vectorError,
                                  RuntimeException keywordError) implements ILegalEvidenceRepository {
        @Override
        public List<LegalEvidence> vectorSearch(LegalRetrievalRequest request) {
            if (vectorError != null) {
                throw vectorError;
            }
            return vector;
        }

        @Override
        public List<LegalEvidence> keywordSearch(LegalRetrievalRequest request) {
            if (keywordError != null) {
                throw keywordError;
            }
            return keyword;
        }
    }
}
