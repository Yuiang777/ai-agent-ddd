package cn.apple.test.domain;

import cn.apple.domain.legal.adapter.repository.ILegalDocumentRepository;
import cn.apple.domain.legal.model.entity.LegalDocument;
import cn.apple.domain.legal.model.entity.LegalDocumentIngestionStats;
import cn.apple.domain.legal.model.valobj.LegalDocumentChunk;
import cn.apple.domain.legal.model.valobj.LegalDocumentSource;
import cn.apple.domain.legal.service.LegalDocumentChunkService;
import cn.apple.domain.legal.service.LegalDocumentIngestionDomainService;
import org.junit.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LegalDocumentIngestionDomainServiceTest {

    @Test
    public void shouldDistinguishProcessedEmptyDocumentsFromFailedDocuments() {
        LegalDocumentSource empty = source("empty.md");
        LegalDocumentSource failed = source("failed.pdf");
        RecordingRepository repository = new RecordingRepository(
                List.of(empty, failed),
                Map.of(empty.docId(), new LegalDocument(empty, "空白法规", "   ")),
                Set.of(failed.docId())
        );
        LegalDocumentIngestionDomainService service = new LegalDocumentIngestionDomainService(
                repository, new LegalDocumentChunkService());

        LegalDocumentIngestionStats stats = service.ingest();

        assertEquals(2, stats.totalDocuments());
        assertEquals(1, stats.successDocuments());
        assertEquals(1, stats.failedDocuments());
        assertTrue(repository.syncCalled);
        assertEquals(Set.of("empty.md", "failed.pdf"), repository.sourceDocumentIds);
        assertEquals(Set.of("empty.md"), repository.processedDocumentIds);
        assertTrue(repository.chunks.isEmpty());
    }

    @Test
    public void shouldSynchronizeAnEmptyWorkspaceSoOrphansCanBeRemoved() {
        RecordingRepository repository = new RecordingRepository(List.of(), Map.of(), Set.of());
        LegalDocumentIngestionDomainService service = new LegalDocumentIngestionDomainService(
                repository, new LegalDocumentChunkService());

        service.ingest();

        assertTrue(repository.syncCalled);
        assertTrue(repository.sourceDocumentIds.isEmpty());
        assertTrue(repository.processedDocumentIds.isEmpty());
    }

    private LegalDocumentSource source(String docId) {
        return new LegalDocumentSource(docId, docId, Path.of("data/legal/raw").resolve(docId));
    }

    private static final class RecordingRepository implements ILegalDocumentRepository {
        private final List<LegalDocumentSource> sources;
        private final Map<String, LegalDocument> documents;
        private final Set<String> failedDocumentIds;
        private boolean syncCalled;
        private List<LegalDocumentChunk> chunks = List.of();
        private Set<String> sourceDocumentIds = Set.of();
        private Set<String> processedDocumentIds = Set.of();

        private RecordingRepository(List<LegalDocumentSource> sources,
                                    Map<String, LegalDocument> documents,
                                    Set<String> failedDocumentIds) {
            this.sources = sources;
            this.documents = new LinkedHashMap<>(documents);
            this.failedDocumentIds = failedDocumentIds;
        }

        @Override
        public void ensureWorkspace() {
        }

        @Override
        public List<LegalDocumentSource> listSources() {
            return sources;
        }

        @Override
        public LegalDocument read(LegalDocumentSource source) {
            if (failedDocumentIds.contains(source.docId())) {
                throw new IllegalStateException("unreadable source");
            }
            return documents.get(source.docId());
        }

        @Override
        public void saveChunks(List<LegalDocumentChunk> chunks) {
        }

        @Override
        public void replaceChunks(List<LegalDocumentChunk> chunks) {
        }

        @Override
        public void synchronizeChunks(List<LegalDocumentChunk> chunks,
                                      Set<String> sourceDocumentIds,
                                      Set<String> processedDocumentIds) {
            this.syncCalled = true;
            this.chunks = List.copyOf(chunks);
            this.sourceDocumentIds = Set.copyOf(sourceDocumentIds);
            this.processedDocumentIds = Set.copyOf(processedDocumentIds);
        }

        @Override
        public void saveManifest(LegalDocumentIngestionStats stats) {
        }
    }
}
