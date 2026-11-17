package cn.apple.domain.legal.service;

import cn.apple.domain.legal.adapter.repository.ILegalDocumentRepository;
import cn.apple.domain.legal.model.entity.LegalDocument;
import cn.apple.domain.legal.model.entity.LegalDocumentIngestionStats;
import cn.apple.domain.legal.model.entity.LegalDocumentIngestionStats.DocumentStat;
import cn.apple.domain.legal.model.valobj.LegalDocumentChunk;
import cn.apple.domain.legal.model.valobj.LegalDocumentSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class LegalDocumentIngestionDomainService {

    private final ILegalDocumentRepository legalDocumentRepository;
    private final LegalDocumentChunkService legalDocumentChunkService;
    private final AtomicBoolean ingestionRunning = new AtomicBoolean();

    public LegalDocumentIngestionStats ingest() {
        if (!ingestionRunning.compareAndSet(false, true)) {
            throw new IllegalStateException("legal document ingestion is already running");
        }
        long start = System.currentTimeMillis();
        try {
            IngestionResult result = collectStats(true);
            legalDocumentRepository.synchronizeChunks(
                    result.chunks(), result.sourceDocumentIds(), result.processedDocumentIds());
            LegalDocumentIngestionStats stats = result.toStats(System.currentTimeMillis() - start);
            legalDocumentRepository.saveManifest(stats);
            return stats;
        } finally {
            ingestionRunning.set(false);
        }
    }

    public LegalDocumentIngestionStats stats() {
        return collectStats(false).toStats(0);
    }

    private IngestionResult collectStats(boolean logFailures) {
        legalDocumentRepository.ensureWorkspace();
        List<LegalDocumentSource> sources = legalDocumentRepository.listSources();
        List<DocumentStat> documentStats = new ArrayList<>();
        Map<String, Integer> chunksByLaw = new LinkedHashMap<>();
        List<LegalDocumentChunk> allChunks = new ArrayList<>();
        Set<String> sourceDocumentIds = new LinkedHashSet<>();
        Set<String> processedDocumentIds = new LinkedHashSet<>();
        int successDocuments = 0;
        int failedDocuments = 0;

        for (LegalDocumentSource source : sources) {
            sourceDocumentIds.add(source.docId());
            try {
                LegalDocument document = legalDocumentRepository.read(source);
                List<LegalDocumentChunk> chunks = legalDocumentChunkService.split(document);
                allChunks.addAll(chunks);
                processedDocumentIds.add(source.docId());
                documentStats.add(new DocumentStat(source.docName(), document.lawName(), chunks.size(), "success", null));
                chunksByLaw.merge(document.lawName(), chunks.size(), Integer::sum);
                successDocuments++;
            } catch (Exception e) {
                failedDocuments++;
                documentStats.add(new DocumentStat(source.docName(), null, 0, "failed", e.getMessage()));
                if (logFailures) {
                    log.warn("legal document ingest failed, file={}", source.sourcePath(), e);
                }
            }
        }

        return new IngestionResult(sources.size(), successDocuments, failedDocuments, documentStats,
                chunksByLaw, allChunks, sourceDocumentIds, processedDocumentIds);
    }

    private record IngestionResult(int totalDocuments,
                                   int successDocuments,
                                   int failedDocuments,
                                   List<DocumentStat> documentStats,
                                   Map<String, Integer> chunksByLaw,
                                   List<LegalDocumentChunk> chunks,
                                   Set<String> sourceDocumentIds,
                                   Set<String> processedDocumentIds) {

        private LegalDocumentIngestionStats toStats(long costMs) {
            return new LegalDocumentIngestionStats(
                    totalDocuments,
                    successDocuments,
                    failedDocuments,
                    chunks.size(),
                    documentStats,
                    chunksByLaw,
                    costMs,
                    LocalDateTime.now()
            );
        }
    }
}
