package cn.apple.infrastructure.legal;

import cn.apple.domain.legal.model.valobj.LegalDocumentChunk;
import cn.apple.domain.legal.model.valobj.LegalKnowledgeMetadata;
import org.junit.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.support.TestPropertySourceUtils;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class LegalDocumentRepositoryMetadataTest {

    @Test
    public void shouldWireRepositoryWithInfrastructureEnabled() {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "app.infrastructure.enabled=true");
            VectorStore vectorStore = (VectorStore) Proxy.newProxyInstance(
                    VectorStore.class.getClassLoader(), new Class<?>[]{VectorStore.class},
                    (proxy, method, args) -> null);
            context.registerBean("vectorStore", VectorStore.class, () -> vectorStore);
            context.registerBean("pgVectorJdbcTemplate", JdbcTemplate.class,
                    () -> new JdbcTemplate(new DriverManagerDataSource()));
            context.register(LegalDocumentRepository.class);
            context.refresh();
            assertNotNull(context.getBean(LegalDocumentRepository.class));
        }
    }

    @Test
    public void shouldMarkFilesystemOwnershipAndVersionCompletenessMetadata() {
        LegalDocumentRepository repository = new LegalDocumentRepository(
                null, new JdbcTemplate(), 64, "vector_store_openai", "data/legal");
        LegalDocumentChunk chunk = new LegalDocumentChunk(
                "《测试法》 第一条：测试内容。",
                "test-law.md",
                "test-law.md",
                "测试法",
                "第一条",
                "",
                0,
                "data/legal/raw/test-law.md",
                "checksum",
                "version",
                LegalKnowledgeMetadata.LEGAL_CHUNK_SCHEMA_VERSION,
                "",
                "第一条"
        );

        Document document = repository.toDocument(
                chunk, 1, LegalKnowledgeMetadata.STATUS_STAGING, "run-1", "2026-08-31T00:00:00Z");

        assertEquals(LegalKnowledgeMetadata.SOURCE_LEGAL_FILESYSTEM,
                document.getMetadata().get(LegalKnowledgeMetadata.INGESTION_SOURCE));
        assertEquals(LegalKnowledgeMetadata.STATUS_STAGING,
                document.getMetadata().get(LegalKnowledgeMetadata.INGESTION_STATUS));
        assertEquals(1, document.getMetadata().get(LegalKnowledgeMetadata.CHUNK_COUNT));
        assertEquals(LegalKnowledgeMetadata.LEGAL_CHUNK_SCHEMA_VERSION,
                document.getMetadata().get(LegalKnowledgeMetadata.CHUNK_SCHEMA_VERSION));
        assertEquals("public_law", document.getMetadata().get(LegalKnowledgeMetadata.CORPUS_TYPE));
        assertEquals("第一条", document.getMetadata().get(LegalKnowledgeMetadata.SECTION_PATH));
    }
}
