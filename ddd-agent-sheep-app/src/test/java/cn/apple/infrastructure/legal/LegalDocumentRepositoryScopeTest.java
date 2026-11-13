package cn.apple.infrastructure.legal;

import cn.apple.domain.legal.model.valobj.LegalCorpusType;
import cn.apple.domain.legal.model.valobj.LegalDocumentScope;
import cn.apple.domain.legal.model.valobj.LegalDocumentSource;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class LegalDocumentRepositoryScopeTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldRecoverPrivateScopeAndObjectUriFromStagingMetadata() throws Exception {
        String root = temporaryFolder.newFolder("legal").getAbsolutePath();
        LegalDocumentRepository repository = new LegalDocumentRepository(
                null, new JdbcTemplate(), 64, "vector_store_openai", root);
        LegalDocumentScope scope = new LegalDocumentScope(
                LegalCorpusType.MATTER_PRIVATE, "tenant-1", "matter-7", "lawyer-3");

        repository.stageSource("document-1", "证据.txt", scope, "s3://legal/document-1",
                new ByteArrayInputStream("第一条 证据内容".getBytes(StandardCharsets.UTF_8)));
        List<LegalDocumentSource> sources = repository.listSources();

        assertEquals(1, sources.size());
        assertEquals("document-1", sources.get(0).docId());
        assertEquals("证据.txt", sources.get(0).docName());
        assertEquals(scope, sources.get(0).scope());
        assertEquals("s3://legal/document-1", sources.get(0).objectUri());
    }

    @Test
    public void shouldRejectPrivateSourceWithoutMandatoryScopeMetadata() throws Exception {
        Path root = temporaryFolder.newFolder("unscoped-legal").toPath();
        Path source = root.resolve("raw/matter_private/tenant-1/matter-7/orphan.txt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "private evidence", StandardCharsets.UTF_8);
        LegalDocumentRepository repository = new LegalDocumentRepository(
                null, new JdbcTemplate(), 64, "vector_store_openai", root.toString());

        assertThrows(IllegalStateException.class, repository::listSources);
    }
}
