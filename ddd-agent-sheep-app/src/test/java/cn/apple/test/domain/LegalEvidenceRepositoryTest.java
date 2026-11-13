package cn.apple.test.domain;

import cn.apple.domain.legal.model.valobj.LegalKnowledgeMetadata;
import cn.apple.domain.legal.model.valobj.LegalRetrievalRequest;
import cn.apple.domain.legal.model.valobj.LegalScopeRoute;
import cn.apple.infrastructure.legal.LegalEvidenceRepository;
import org.junit.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
