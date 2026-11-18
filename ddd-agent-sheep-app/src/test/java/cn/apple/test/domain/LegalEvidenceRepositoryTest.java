package cn.apple.test.domain;

import cn.apple.domain.legal.model.valobj.LegalKnowledgeMetadata;
import cn.apple.domain.legal.model.valobj.LegalCorpusType;
import cn.apple.domain.legal.model.valobj.LegalRetrievalRequest;
import cn.apple.domain.legal.model.valobj.LegalScopeRoute;
import cn.apple.infrastructure.legal.LegalEvidenceRepository;
import org.junit.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LegalEvidenceRepositoryTest {

    private final LegalScopeRoute route = new LegalScopeRoute(
            "civil_contract",
            "民商事合同",
            List.of("民法典"),
            List.of("违约责任", "合同履行"),
            0.9,
            "test"
    );

    @Test
    public void shouldBuildChineseTrigramQueryWithBoundFiltersAndReferenceBoosts() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        LegalEvidenceRepository repository = new LegalEvidenceRepository(null, jdbcTemplate, "vector_store_openai");
        LegalRetrievalRequest request = new LegalRetrievalRequest(
                "民法典第五百七十七条违约责任",
                "status == 'effective'",
                6,
                12,
                18,
                route,
                "legal-knowledge",
                "tenant-001"
        );

        repository.keywordSearch(request);

        assertTrue(jdbcTemplate.sql.contains("word_similarity"));
        assertTrue(jdbcTemplate.sql.contains("content % ?"));
        assertFalse(jdbcTemplate.sql.contains("word_similarity(?, content) >="));
        assertTrue(jdbcTemplate.sql.contains("metadata ->> 'article_no' = ?"));
        assertFalse(jdbcTemplate.sql.contains("to_tsvector"));
        assertTrue(jdbcTemplate.parameters.contains("legal-knowledge"));
        assertTrue(jdbcTemplate.parameters.contains("tenant-001"));
        assertTrue(jdbcTemplate.parameters.contains(LegalKnowledgeMetadata.STATUS_ACTIVE));
        assertTrue(jdbcTemplate.parameters.contains("effective"));
        assertTrue(jdbcTemplate.parameters.contains("第五百七十七条"));
        assertTrue(jdbcTemplate.parameters.contains("%民法典%"));
        assertTrue(jdbcTemplate.parameters.contains(18));
    }

    @Test
    public void shouldApplyTheSameMandatoryFiltersToVectorSearch() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        LegalEvidenceRepository repository = new LegalEvidenceRepository(
                vectorStore, new RecordingJdbcTemplate(), "vector_store_openai");
        LegalRetrievalRequest request = new LegalRetrievalRequest(
                "合同违约", "status == 'effective'", 6, 12, 18,
                route, "legal-knowledge", "tenant-001");

        repository.vectorSearch(request);

        assertNotNull(vectorStore.request);
        assertTrue(vectorStore.request.hasFilterExpression());
        String expression = vectorStore.request.getFilterExpression().toString();
        assertTrue(vectorStore.request.getQuery().contains(route.queryExpansionTerms().get(0)));
        assertTrue(expression.contains(LegalKnowledgeMetadata.KNOWLEDGE));
        assertTrue(expression.contains(LegalKnowledgeMetadata.TENANT_ID));
        assertTrue(expression.contains(LegalKnowledgeMetadata.INGESTION_STATUS));
        assertTrue(expression.contains("status"));
    }

    @Test
    public void shouldRestrictDefaultSearchesToActivePublicLaw() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        LegalEvidenceRepository repository = new LegalEvidenceRepository(
                vectorStore, jdbcTemplate, "vector_store_openai");
        LegalRetrievalRequest request = new LegalRetrievalRequest(
                "个人信息保护", null, 6, 12, 18);

        repository.vectorSearch(request);
        repository.keywordSearch(request);

        assertEquals(new FilterExpressionTextParser().parse(
                "knowledge == 'law-consultant' && ingestion_status == 'active' && corpus_type == 'public_law'"),
                vectorStore.request.getFilterExpression());
        assertTrue(jdbcTemplate.sql.contains("and metadata ->> 'knowledge' = ?"));
        assertTrue(jdbcTemplate.sql.contains("and metadata ->> 'ingestion_status' = ?"));
        assertTrue(jdbcTemplate.sql.contains("and metadata ->> 'corpus_type' = ?"));
        int filterStart = jdbcTemplate.parameters.indexOf("law-consultant");
        assertTrue(filterStart >= 0);
        assertEquals(List.of("law-consultant", "active", "public_law"),
                jdbcTemplate.parameters.subList(filterStart, filterStart + 3));
    }

    @Test
    public void shouldBindPrivateMatterAndActorIsolationInBothSearches() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        LegalEvidenceRepository repository = new LegalEvidenceRepository(
                vectorStore, jdbcTemplate, "vector_store_openai");
        LegalRetrievalRequest request = new LegalRetrievalRequest(
                "合同履行证据", null, 6, 12, 18, route, "law-consultant",
                "tenant-001", LegalCorpusType.MATTER_PRIVATE, "matter-009", "lawyer-7");

        repository.vectorSearch(request);
        repository.keywordSearch(request);

        assertEquals(new FilterExpressionTextParser().parse(
                "knowledge == 'law-consultant' && ingestion_status == 'active' && corpus_type == 'matter_private'"
                        + " && tenant_id == 'tenant-001' && matter_id == 'matter-009' && acl_subject_id == 'lawyer-7'"),
                vectorStore.request.getFilterExpression());
        assertTrue(jdbcTemplate.sql.contains("and metadata ->> 'tenant_id' = ?"));
        assertTrue(jdbcTemplate.sql.contains("and metadata ->> 'matter_id' = ?"));
        assertTrue(jdbcTemplate.sql.contains("and metadata ->> 'acl_subject_id' = ?"));
        assertFalse(jdbcTemplate.sql.contains("tenant-001"));
        assertFalse(jdbcTemplate.sql.contains("matter-009"));
        assertFalse(jdbcTemplate.sql.contains("lawyer-7"));
        int filterStart = jdbcTemplate.parameters.indexOf("law-consultant");
        assertTrue(filterStart >= 0);
        assertEquals(List.of("law-consultant", "active", "matter_private",
                        "tenant-001", "matter-009", "lawyer-7"),
                jdbcTemplate.parameters.subList(filterStart, filterStart + 6));
    }

    @Test
    public void shouldRejectScopeOverridesBeforeEitherRepositoryCall() {
        for (String filter : List.of(
                "ingestion_status == 'staging'",
                "corpus_type == 'public_law'",
                "tenant_id == 'other-tenant'",
                "matter_id == 'other-matter'",
                "acl_subject_id == 'other-actor'")) {
            RecordingVectorStore vectorStore = new RecordingVectorStore();
            RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
            LegalEvidenceRepository repository = new LegalEvidenceRepository(
                    vectorStore, jdbcTemplate, "vector_store_openai");
            LegalRetrievalRequest request = new LegalRetrievalRequest(
                    "合同履行证据", filter, 6, 12, 18, route, "law-consultant",
                    "tenant-001", LegalCorpusType.MATTER_PRIVATE, "matter-009", "lawyer-7");

            assertThrows(IllegalArgumentException.class, () -> repository.vectorSearch(request));
            assertThrows(IllegalArgumentException.class, () -> repository.keywordSearch(request));
            assertNull(vectorStore.request);
            assertEquals("", jdbcTemplate.sql);
        }
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private String sql = "";
        private List<Object> parameters = List.of();

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            this.parameters = List.of(args);
            return List.of();
        }
    }

    private static final class RecordingVectorStore implements VectorStore {
        private SearchRequest request;

        @Override
        public void add(List<Document> documents) {
        }

        @Override
        public void delete(List<String> idList) {
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            this.request = request;
            return new ArrayList<>();
        }
    }
}
