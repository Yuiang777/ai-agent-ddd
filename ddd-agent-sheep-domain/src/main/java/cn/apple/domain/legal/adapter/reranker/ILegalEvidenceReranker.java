package cn.apple.domain.legal.adapter.reranker;

import cn.apple.domain.legal.model.valobj.LegalEvidence;
import cn.apple.domain.legal.model.valobj.LegalScopeRoute;

import java.util.List;

public interface ILegalEvidenceReranker {

    List<LegalEvidence> rerank(String query,
                               LegalScopeRoute route,
                               List<LegalEvidence> candidates,
                               int limit);
}
