package cn.apple.domain.improvement.service;

import cn.apple.domain.improvement.model.EvaluationSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class OfflineImprovementGate {
    public enum Decision { KEEP_BASELINE, PENDING_HUMAN_REVIEW }

    public record Comparison(Decision decision, String reason, double baselineScore, double candidateScore) {}

    public Comparison compare(EvaluationSnapshot baseline, EvaluationSnapshot candidate) {
        Objects.requireNonNull(baseline, "Baseline is required");
        Objects.requireNonNull(candidate, "Candidate is required");
        if (baseline.harness().versionId().equals(candidate.harness().versionId())
                || !baseline.harness().modelId().equals(candidate.harness().modelId())
                || !baseline.inputSnapshotSha256().equals(candidate.inputSnapshotSha256())
                || baseline.tokenBudget() != candidate.tokenBudget()) {
            throw new IllegalArgumentException("Comparison requires distinct versions and identical model, input and budget");
        }
        Map<String, EvaluationSnapshot.CaseResult> baselineCases = index(baseline);
        Map<String, EvaluationSnapshot.CaseResult> candidateCases = index(candidate);
        if (!baselineCases.keySet().equals(candidateCases.keySet())) {
            throw new IllegalArgumentException("Evaluation case coverage differs");
        }
        double baselineScore = score(baselineCases);
        double candidateScore = score(candidateCases);
        if (!withinBudget(baseline) || !withinBudget(candidate)) {
            return new Comparison(Decision.KEEP_BASELINE, "BUDGET_EXCEEDED", baselineScore, candidateScore);
        }
        for (var entry : candidateCases.entrySet()) {
            if (!entry.getValue().passesSafetyGate()) {
                return new Comparison(Decision.KEEP_BASELINE, "SAFETY_OR_COMPLETION_FAILED", baselineScore, candidateScore);
            }
            if (entry.getValue().qualityScore() < baselineCases.get(entry.getKey()).qualityScore()) {
                return new Comparison(Decision.KEEP_BASELINE, "CASE_QUALITY_REGRESSION", baselineScore, candidateScore);
            }
        }
        if (candidateScore <= baselineScore) {
            return new Comparison(Decision.KEEP_BASELINE, "NO_MEASURABLE_GAIN", baselineScore, candidateScore);
        }
        return new Comparison(Decision.PENDING_HUMAN_REVIEW, "OFFLINE_GAIN_REQUIRES_REVIEW", baselineScore, candidateScore);
    }

    private Map<String, EvaluationSnapshot.CaseResult> index(EvaluationSnapshot snapshot) {
        Map<String, EvaluationSnapshot.CaseResult> result = new LinkedHashMap<>();
        snapshot.cases().forEach(item -> result.put(item.caseId(), item));
        return result;
    }

    private double score(Map<String, EvaluationSnapshot.CaseResult> cases) {
        return cases.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .mapToDouble(entry -> entry.getValue().qualityScore()).average().orElseThrow();
    }

    private boolean withinBudget(EvaluationSnapshot snapshot) {
        return snapshot.cases().stream().mapToLong(EvaluationSnapshot.CaseResult::tokensUsed).sum()
                <= snapshot.tokenBudget();
    }
}
