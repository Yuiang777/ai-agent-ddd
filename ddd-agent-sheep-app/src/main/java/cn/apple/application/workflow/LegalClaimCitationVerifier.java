package cn.apple.application.workflow;

import cn.apple.domain.legal.model.valobj.LegalEvidence;
import cn.apple.domain.legal.model.valobj.LegalKnowledgeMetadata;

import java.time.LocalDate;
import java.time.Clock;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LegalClaimCitationVerifier {
    private final Clock clock;

    public LegalClaimCitationVerifier() {
        this(Clock.systemDefaultZone());
    }

    public LegalClaimCitationVerifier(Clock clock) {
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    public Verification verify(List<Claim> claims, List<LegalEvidence> evidences) {
        if (claims == null || claims.isEmpty() || claims.size() > 20) {
            return new Verification(false, List.of(), List.of("法律主张缺失或超出核验上限"));
        }
        LocalDate verificationDate = LocalDate.now(clock);
        List<Finding> findings = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (int index = 0; index < claims.size(); index++) {
            Claim claim = claims.get(index);
            LegalEvidence evidence = evidenceFor(claim, evidences);
            String status = check(claim, evidence, verificationDate);
            findings.add(new Finding(index + 1, claim, evidence == null ? "" : evidence.docId(), status));
            if (!"SOURCE_MATCHED".equals(status)) {
                reasons.add("第" + (index + 1) + "条主张引用核验失败：" + status);
            }
        }
        return new Verification(reasons.isEmpty(), List.copyOf(findings), List.copyOf(reasons));
    }

    private LegalEvidence evidenceFor(Claim claim, List<LegalEvidence> evidences) {
        if (claim == null || evidences == null || claim.evidenceId() == null) return null;
        for (int index = 0; index < evidences.size(); index++) {
            if (("E" + (index + 1)).equals(claim.evidenceId())) return evidences.get(index);
        }
        return null;
    }

    private String check(Claim claim, LegalEvidence evidence, LocalDate verificationDate) {
        if (claim == null || blank(claim.text()) || blank(claim.evidenceId()) || blank(claim.quote()))
            return "CLAIM_OR_REFERENCE_MISSING";
        if (claim.text().length() > 1200 || claim.quote().length() > 360
                || normalized(claim.quote()).codePoints().filter(Character::isLetterOrDigit).count() < 8)
            return "CLAIM_OR_QUOTE_LENGTH_INVALID";
        if (evidence == null) return "EVIDENCE_ID_UNKNOWN";
        if (blank(evidence.id()) || blank(evidence.docId()) || blank(evidence.docName()) || blank(evidence.sourcePath()))
            return "SOURCE_LOCATION_MISSING";
        Map<String, Object> metadata = evidence.metadata();
        if (metadata == null || blank(value(metadata.get(LegalKnowledgeMetadata.DOCUMENT_VERSION)))
                || blank(value(metadata.get(LegalKnowledgeMetadata.SOURCE_CHECKSUM))))
            return "SOURCE_VERSION_MISSING";
        String effectiveDate = value(metadata.get(LegalKnowledgeMetadata.EFFECTIVE_DATE));
        if (!effectiveDate.isBlank()) {
            try {
                if (LocalDate.parse(effectiveDate).isAfter(verificationDate)) return "SOURCE_NOT_YET_EFFECTIVE";
            } catch (DateTimeParseException exception) {
                return "SOURCE_EFFECTIVE_DATE_INVALID";
            }
        }
        if (blank(evidence.text()) || !normalized(evidence.text()).contains(normalized(claim.quote())))
            return "QUOTE_NOT_IN_SOURCE";
        return "SOURCE_MATCHED";
    }

    private String normalized(String text) {
        return text == null ? "" : text.replaceAll("(?U)\\s+", "").trim();
    }

    private String value(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private boolean blank(String text) {
        return text == null || text.isBlank();
    }

    public record Claim(String text, String evidenceId, String quote) {}
    public record Finding(int number, Claim claim, String documentId, String status) {}
    public record Verification(boolean traceable, List<Finding> findings, List<String> reasons) {}
}
