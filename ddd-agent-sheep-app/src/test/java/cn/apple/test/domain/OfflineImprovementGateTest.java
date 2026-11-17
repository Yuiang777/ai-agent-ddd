package cn.apple.test.domain;

import cn.apple.domain.improvement.model.EvaluationSnapshot;
import cn.apple.domain.improvement.model.HarnessVersion;
import cn.apple.domain.improvement.service.OfflineImprovementGate;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class OfflineImprovementGateTest {
    private final OfflineImprovementGate gate = new OfflineImprovementGate();
    private static final String HASH = "a".repeat(64);

    @Test
    public void improvementOnlyBecomesPendingReview() {
        var result = gate.compare(snapshot("v1", item("a", .5)), snapshot("v2", item("a", .8)));
        assertEquals(OfflineImprovementGate.Decision.PENDING_HUMAN_REVIEW, result.decision());
    }

    @Test
    public void tiesAndPerCaseRegressionsKeepBaseline() {
        assertEquals("NO_MEASURABLE_GAIN", gate.compare(snapshot("v1", item("a", .5)), snapshot("v2", item("a", .5))).reason());
        assertEquals("CASE_QUALITY_REGRESSION", gate.compare(snapshot("v1", item("a", .5), item("b", .5)),
                snapshot("v2", item("a", .4), item("b", 1))).reason());
    }

    @Test
    public void eachSafetyCheckAndIncompleteRunBlocksPromotion() {
        for (int index = 0; index < 4; index++) {
            var unsafe = new EvaluationSnapshot.CaseResult("a", 1, index != 0, index != 1, index != 2, index != 3, 10);
            assertEquals("SAFETY_OR_COMPLETION_FAILED", gate.compare(snapshot("v1", item("a", .5)), snapshot("v2", unsafe)).reason());
        }
    }

    @Test
    public void changedModelInputBudgetVersionOrCoverageIsNotComparable() {
        var baseline = snapshot("v1", item("a", .5));
        assertThrows(IllegalArgumentException.class, () -> gate.compare(baseline, baseline));
        assertThrows(IllegalArgumentException.class, () -> gate.compare(baseline, snapshot("v2", item("b", .8))));
        assertThrows(IllegalArgumentException.class, () -> gate.compare(baseline,
                new EvaluationSnapshot(new HarnessVersion("v2", "other-model", "c1", 6, 6000), HASH, 100, List.of(item("a", .8)))));
        assertThrows(IllegalArgumentException.class, () -> gate.compare(baseline,
                new EvaluationSnapshot(version("v2"), "b".repeat(64), 100, List.of(item("a", .8)))));
        assertThrows(IllegalArgumentException.class, () -> gate.compare(baseline,
                new EvaluationSnapshot(version("v2"), HASH, 101, List.of(item("a", .8)))));
    }

    @Test
    public void tokenBudgetIsTotalAcrossCases() {
        var costly = new EvaluationSnapshot.CaseResult("a", 1, true, true, true, true, 101);
        assertEquals("BUDGET_EXCEEDED", gate.compare(snapshot("v1", item("a", .5)), snapshot("v2", costly)).reason());
    }

    @Test
    public void invalidMetricsAndEmptyDuplicateOrMutableEvidenceAreRejected() {
        for (double score : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -.1, 1.1}) {
            assertThrows(IllegalArgumentException.class, () -> item("a", score));
        }
        assertThrows(IllegalArgumentException.class, () -> snapshot("v1"));
        assertThrows(IllegalArgumentException.class, () -> snapshot("v1", item("a", .5), item("a", .8)));
        var cases = new ArrayList<>(List.of(item("a", .5)));
        var snapshot = new EvaluationSnapshot(version("v1"), HASH, 100, cases);
        cases.clear();
        assertEquals(1, snapshot.cases().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.cases().clear());
    }

    @Test
    public void caseOrderDoesNotChangeTheComparison() {
        var baseline = snapshot("v1", item("a", .5), item("b", .5));
        assertEquals(gate.compare(baseline, snapshot("v2", item("a", .6), item("b", .8))),
                gate.compare(baseline, snapshot("v2", item("b", .8), item("a", .6))));
    }

    private HarnessVersion version(String id) {
        return new HarnessVersion(id, "offline-model", "checklist-v1", 6, 6000);
    }

    private EvaluationSnapshot snapshot(String id, EvaluationSnapshot.CaseResult... cases) {
        return new EvaluationSnapshot(version(id), HASH, 100, List.of(cases));
    }

    private EvaluationSnapshot.CaseResult item(String id, double score) {
        return new EvaluationSnapshot.CaseResult(id, score, true, true, true, true, 10);
    }
}
