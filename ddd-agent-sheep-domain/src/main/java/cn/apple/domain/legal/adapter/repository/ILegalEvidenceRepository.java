package cn.apple.domain.legal.adapter.repository;

import cn.apple.domain.legal.model.valobj.LegalEvidence;
import cn.apple.domain.legal.model.valobj.LegalRetrievalRequest;

import java.util.List;

public interface ILegalEvidenceRepository {

    List<LegalEvidence> vectorSearch(LegalRetrievalRequest request);

    List<LegalEvidence> keywordSearch(LegalRetrievalRequest request);
}
