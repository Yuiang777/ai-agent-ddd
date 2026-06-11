package cn.apple.domain.legal.adapter.repository;

import cn.apple.domain.legal.model.entity.LegalDocument;
import cn.apple.domain.legal.model.entity.LegalDocumentIngestionStats;
import cn.apple.domain.legal.model.valobj.LegalDocumentChunk;
import cn.apple.domain.legal.model.valobj.LegalDocumentSource;

import java.util.List;

public interface ILegalDocumentRepository {

    void ensureWorkspace();

    List<LegalDocumentSource> listSources();

    LegalDocument read(LegalDocumentSource source);

    void saveChunks(List<LegalDocumentChunk> chunks);

    void saveManifest(LegalDocumentIngestionStats stats);
}
