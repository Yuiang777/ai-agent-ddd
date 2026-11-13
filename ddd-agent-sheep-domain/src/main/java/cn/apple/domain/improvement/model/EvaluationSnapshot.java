package cn.apple.domain.improvement.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record EvaluationSnapshot(HarnessVersion harness, String inputSnapshotSha256,
                                 long tokenBudget, List<CaseResult> cases) {
    public EvaluationSnapshot {
        Objects.requireNonNull(harness, "Harness is required");
        if (inputSnapshotSha256 == null || !inputSnapshotSha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("A frozen input SHA-256 is required");
        }
        if (tokenBudget < 1 || tokenBudget > 1000000) {
            throw new IllegalArgumentException("Invalid offline token budget");
        }
        cases = List.copyOf(cases);
        if (cases.isEmpty()) throw new IllegalArgumentException("Empty evaluation is not evidence");
        var identifiers = new HashSet<String>();
        for (CaseResult result : cases) {
            if (!identifiers.add(result.caseId())) {
                throw new IllegalArgumentException("Duplicate evaluation case");
            }
        }
    }

    public record CaseResult(String caseId, double qualityScore, boolean citationSupported,
                             boolean authorizationPassed, boolean refusalCorrect,
                             boolean completed, long tokensUsed) {
        public CaseResult {
            caseId = HarnessVersion.required(caseId);
            if (!Double.isFinite(qualityScore) || qualityScore < 0 || qualityScore > 1
                    || tokensUsed < 0 || tokensUsed > 1000000) {
                throw new IllegalArgumentException("Invalid case metrics");
            }
        }

        public boolean passesSafetyGate() {
            return completed && citationSupported && authorizationPassed && refusalCorrect;
        }
    }
}
