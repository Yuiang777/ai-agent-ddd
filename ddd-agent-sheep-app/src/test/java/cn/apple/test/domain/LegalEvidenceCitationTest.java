package cn.apple.test.domain;

import cn.apple.domain.legal.model.valobj.LegalCorpusType;
import cn.apple.domain.legal.model.valobj.LegalEvidence;
import cn.apple.domain.legal.model.valobj.LegalKnowledgeMetadata;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;

public class LegalEvidenceCitationTest {

    @Test
    public void shouldCitePublicLawByLawAndArticle() {
        LegalEvidence evidence = evidence(
                "民法典.md",
                "民法典",
                "第五百六十三条",
                "第一款",
                Map.of(LegalKnowledgeMetadata.CORPUS_TYPE, LegalCorpusType.PUBLIC_LAW.code()));

        assertEquals("《民法典》第五百六十三条第一款", evidence.citation());
    }

    @Test
    public void shouldCiteCaseByDocumentAndSectionPath() {
        LegalEvidence evidence = evidence(
                "（2026）京01民终100号民事判决书.pdf",
                "",
                "",
                "",
                Map.of(
                        LegalKnowledgeMetadata.CORPUS_TYPE, LegalCorpusType.PUBLIC_CASE.code(),
                        LegalKnowledgeMetadata.SECTION_PATH, "本院认为"));

        assertEquals("（2026）京01民终100号民事判决书.pdf / 本院认为", evidence.citation());
    }

    private LegalEvidence evidence(String docName,
                                   String lawName,
                                   String articleNo,
                                   String clauseNo,
                                   Map<String, Object> metadata) {
        return new LegalEvidence(
                "evidence-1",
                "证据正文",
                "doc-1",
                docName,
                lawName,
                articleNo,
                clauseNo,
                0,
                "/legal/" + docName,
                0.9,
                metadata);
    }
}
