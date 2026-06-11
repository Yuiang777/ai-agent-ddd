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

@Slf4j
@Service
@RequiredArgsConstructor
public class LegalDocumentIngestionDomainService {

    private final ILegalDocumentRepository legalDocumentRepository;
    private final LegalDocumentChunkService legalDocumentChunkService;

    public LegalDocumentIngestionStats ingest() {
        long start = System.currentTimeMillis();
        IngestionResult result = collectStats(true);
        if (!result.chunks().isEmpty()) {
            legalDocumentRepository.saveChunks(result.chunks());
        }
        LegalDocumentIngestionStats stats = result.toStats(System.currentTimeMillis() - start);
        legalDocumentRepository.saveManifest(stats);
        return stats;
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
        int successDocuments = 0;
        int failedDocuments = 0;

        for (LegalDocumentSource source : sources) {
            try {
                LegalDocument document = legalDocumentRepository.read(source);
                List<LegalDocumentChunk> chunks = legalDocumentChunkService.split(document);
                allChunks.addAll(chunks);
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

        return new IngestionResult(sources.size(), successDocuments, failedDocuments, documentStats, chunksByLaw, allChunks);
    }

    private record IngestionResult(int totalDocuments,
                                   int successDocuments,
                                   int failedDocuments,
                                   List<DocumentStat> documentStats,
                                   Map<String, Integer> chunksByLaw,
                                   List<LegalDocumentChunk> chunks) {

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
