package cn.apple.domain.legal.model.valobj;

import java.util.List;

public record LegalContextAssembly(String context,
                                   List<LegalEvidence> evidences,
                                   int contextChars,
                                   int droppedEvidenceCount) {
}
