package cn.apple.test.domain;

import cn.apple.application.workflow.LegalClaimCitationVerifier;
import cn.apple.domain.legal.model.valobj.LegalEvidence;
import cn.apple.domain.legal.model.valobj.LegalKnowledgeMetadata;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LegalClaimCitationVerifierTest {
    private final LegalClaimCitationVerifier verifier = new LegalClaimCitationVerifier();
    private final String excerpt = "当事人应当按照约定全面履行义务";

    @Test
    public void matchesOriginalTextWithStableSourceMetadata() {
        LegalClaimCitationVerifier.Verification result = verifier.verify(List.of(claim("E1", excerpt)),
                List.of(evidence(metadata("2020-01-01"))));

        assertTrue(result.traceable());
        assertEquals("SOURCE_MATCHED", result.findings().get(0).status());
        assertEquals("document-1", result.findings().get(0).documentId());
    }

    @Test
    public void matchesChineseFullWidthAndNonBreakingWhitespaceInQuotation() {
        String spacedQuote = excerpt.substring(0, 3) + (char) 0x3000 + excerpt.substring(3, 7)
                + (char) 0x00a0 + excerpt.substring(7);
        LegalClaimCitationVerifier.Verification result = verifier.verify(List.of(claim("E1", spacedQuote)),
                List.of(evidence(metadata("2020-01-01"))));

        assertTrue(result.traceable());
        assertEquals("SOURCE_MATCHED", result.findings().get(0).status());
    }

    @Test
    public void failsClosedOnUnknownEvidenceOrInventedQuotation() {
        LegalClaimCitationVerifier.Verification result = verifier.verify(List.of(
                claim("E2", excerpt), claim("E1", "这是一段并不存在于来源中的法条原文")),
                List.of(evidence(metadata("2020-01-01"))));

        assertFalse(result.traceable());
        assertEquals("EVIDENCE_ID_UNKNOWN", result.findings().get(0).status());
        assertEquals("QUOTE_NOT_IN_SOURCE", result.findings().get(1).status());
    }

    @Test
    public void rejectsMissingVersionAndInvalidOrFutureEffectiveDate() {
        assertEquals("SOURCE_VERSION_MISSING", verifier.verify(List.of(claim("E1", excerpt)),
                List.of(evidence(Map.of()))).findings().get(0).status());
        assertEquals("SOURCE_NOT_YET_EFFECTIVE", verifier.verify(List.of(claim("E1", excerpt)),
                List.of(evidence(metadata("2999-01-01")))).findings().get(0).status());
        assertEquals("SOURCE_EFFECTIVE_DATE_INVALID", verifier.verify(List.of(claim("E1", excerpt)),
                List.of(evidence(metadata("invalid-date")))).findings().get(0).status());
    }

    @Test
    public void usesInjectedClockForEffectiveDateChecks() {
        LegalClaimCitationVerifier fixedVerifier = new LegalClaimCitationVerifier(
                Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC));
        assertEquals("SOURCE_NOT_YET_EFFECTIVE", fixedVerifier.verify(List.of(claim("E1", excerpt)),
                List.of(evidence(metadata("2024-01-02")))).findings().get(0).status());
    }

    @Test
    public void rejectsMalformedClaimsAndNoClaims() {
        assertFalse(verifier.verify(List.of(), List.of()).traceable());
        assertEquals("CLAIM_OR_REFERENCE_MISSING", verifier.verify(
                java.util.Arrays.asList((LegalClaimCitationVerifier.Claim) null),
                List.of(evidence(metadata("2020-01-01")))).findings().get(0).status());
    }

    private LegalClaimCitationVerifier.Claim claim(String id, String quote) {
        return new LegalClaimCitationVerifier.Claim("候选法律主张", id, quote);
    }

    private LegalEvidence evidence(Map<String, Object> metadata) {
        return new LegalEvidence("chunk-1", "示例来源：" + excerpt + "。", "document-1", "示例文档",
                "", "第一条", "", 0, "/example/source", 0.9, metadata);
    }

    private Map<String, Object> metadata(String effectiveDate) {
        return Map.of(LegalKnowledgeMetadata.DOCUMENT_VERSION, "v1",
                LegalKnowledgeMetadata.SOURCE_CHECKSUM, "checksum-1",
                LegalKnowledgeMetadata.EFFECTIVE_DATE, effectiveDate);
    }
}
