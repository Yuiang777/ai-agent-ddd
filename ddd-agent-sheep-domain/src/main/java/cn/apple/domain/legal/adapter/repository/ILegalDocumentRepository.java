package cn.apple.domain.legal.adapter.repository;

import cn.apple.domain.legal.model.entity.LegalDocument;
import cn.apple.domain.legal.model.entity.LegalDocumentIngestionStats;
import cn.apple.domain.legal.model.valobj.LegalDocumentChunk;
import cn.apple.domain.legal.model.valobj.LegalDocumentSource;

import java.util.List;
import java.util.Set;
import java.io.InputStream;
import cn.apple.domain.legal.model.valobj.LegalDocumentScope;

public interface ILegalDocumentRepository {

    void ensureWorkspace();

    List<LegalDocumentSource> listSources();

    LegalDocument read(LegalDocumentSource source);

    default LegalDocumentSource stageSource(String documentId,
                                            String originalName,
                                            LegalDocumentScope scope,
                                            String objectUri,
                                            InputStream content) {
        throw new UnsupportedOperationException("legal document staging is not supported by this repository");
    }

    void saveChunks(List<LegalDocumentChunk> chunks);

    void replaceChunks(List<LegalDocumentChunk> chunks);

    void synchronizeChunks(List<LegalDocumentChunk> chunks,
                           Set<String> sourceDocumentIds,
                           Set<String> processedDocumentIds);

    void saveManifest(LegalDocumentIngestionStats stats);
}
