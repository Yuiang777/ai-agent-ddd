package cn.apple.test.domain;

import cn.apple.domain.legal.matter.DeadlineBasis;
import cn.apple.domain.legal.matter.EvidencePreservationStatus;
import cn.apple.domain.legal.matter.EvidenceSourceType;
import cn.apple.domain.legal.matter.LegalMatter;
import cn.apple.domain.legal.matter.LegalReviewTask;
import cn.apple.domain.legal.matter.MatterActorRole;
import cn.apple.domain.legal.matter.MatterDeadline;
import cn.apple.domain.legal.matter.MatterEvidence;
import cn.apple.domain.legal.matter.MatterStatus;
import cn.apple.domain.legal.matter.MatterTransitionCommand;
import cn.apple.domain.legal.matter.ReviewTaskDecision;
import cn.apple.domain.legal.matter.ReviewTaskType;
import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LegalMatterLifecycleTest {
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    public void shouldRequireLawyerForSignoffAndKeepTransitionAudit() {
        LegalMatter matter = LegalMatter.open("tenant-a", "Contract dispute", "client-1", NOW);
        matter.transitionTo(MatterStatus.MATERIAL_PENDING, MatterTransitionCommand.system("collect materials", NOW));
        matter.transitionTo(MatterStatus.ANALYSIS, command("agent-1", MatterActorRole.PARALEGAL, "materials complete", "", 1));
        matter.transitionTo(MatterStatus.REVIEW_REQUIRED, command("agent-1", MatterActorRole.SYSTEM, "independent review", "", 2));
        matter.transitionTo(MatterStatus.SIGNOFF_REQUIRED, command("reviewer-1", MatterActorRole.LAWYER, "ready for signoff", "REV-1", 3));

        try {
            matter.transitionTo(MatterStatus.SIGNED_OFF, command("reviewer-1", MatterActorRole.PARALEGAL, "approved", "SIGN-1", 4));
        } catch (SecurityException expected) {
            assertEquals(MatterStatus.SIGNOFF_REQUIRED, matter.status());
        }
        matter.transitionTo(MatterStatus.SIGNED_OFF, command("lawyer-1", MatterActorRole.LAWYER, "verified facts and authorities", "SIGN-1", 4));
        assertEquals(5, matter.version());
        assertEquals("SIGN-1", matter.transitionHistory().get(4).reference());
    }

    @Test(expected = IllegalStateException.class)
    public void shouldRejectStaleMatterVersion() {
        LegalMatter matter = LegalMatter.open("tenant-a", "Advice", "client-1", NOW);
        matter.transitionTo(MatterStatus.MATERIAL_PENDING, MatterTransitionCommand.system("collect materials", NOW));
        matter.transitionTo(MatterStatus.ANALYSIS, command("user-1", MatterActorRole.PARALEGAL, "ready", "", 0));
    }

    @Test
    public void shouldMakeReviewAndSignoffDecisionsExplicit() {
        LegalReviewTask task = LegalReviewTask.create("REV-1", "tenant-a", "MAT-1", ReviewTaskType.LAWYER_SIGNOFF, NOW);
        task.start("lawyer-1", MatterActorRole.LAWYER);
        task.decide(new ReviewTaskDecision("lawyer-1", MatterActorRole.LAWYER, true,
                "facts, evidence and citations checked", "SIGN-1", NOW.plusSeconds(10)));
        assertEquals("COMPLETED", task.status().name());
        assertEquals(2, task.version());
    }

    @Test
    public void shouldRejectUnauthorizedSignoffStartWithoutMutation() {
        LegalReviewTask task = LegalReviewTask.create("R", "t", "M", ReviewTaskType.LAWYER_SIGNOFF, NOW);
        org.junit.Assert.assertThrows(SecurityException.class, () -> task.start("assistant", MatterActorRole.PARALEGAL));
        assertEquals("PENDING", task.status().name());
        assertEquals(0, task.version());
        assertEquals("", task.reviewerId());
    }

    @Test
    public void shouldKeepInvalidDecisionFromMutatingDomainTask() {
        LegalReviewTask task = LegalReviewTask.create("R", "t", "M", ReviewTaskType.LAWYER_SIGNOFF, NOW);
        task.start("lawyer", MatterActorRole.LAWYER);
        org.junit.Assert.assertThrows(SecurityException.class, () -> task.decide(
                new ReviewTaskDecision("other", MatterActorRole.LAWYER, true, "checked", "REF", NOW)));
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> task.decide(
                new ReviewTaskDecision("lawyer", MatterActorRole.LAWYER, true, "checked", "", NOW)));
        assertEquals("IN_REVIEW", task.status().name());
        assertEquals(1, task.version());
        assertEquals("lawyer", task.reviewerId());
        assertEquals("", task.opinion());
        assertEquals("", task.decisionReference());
        task.decide(new ReviewTaskDecision("lawyer", MatterActorRole.LAWYER, true, "checked", "REF", NOW));
        assertEquals("COMPLETED", task.status().name());
    }

    @Test
    public void shouldTrackEvidenceIntegrityCustodyAndIssueLinks() {
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        MatterEvidence evidence = new MatterEvidence("E-1", "tenant-a", "MAT-1", 1, "s3://bucket/e-1", hash,
                EvidenceSourceType.CLIENT_SUBMITTED, NOW, "intake-user", EvidencePreservationStatus.UNVERIFIED, Set.of());
        MatterEvidence linked = evidence.associateIssue("ISSUE-1").markPreserved("custodian-1");
        assertTrue(linked.issueIds().contains("ISSUE-1"));
        assertEquals(EvidencePreservationStatus.PRESERVED, linked.preservationStatus());
        assertEquals(3, linked.version());
    }

    @Test
    public void shouldKeepUnverifiedDeadlinesNonActionable() {
        MatterDeadline deadline = new MatterDeadline("D-1", "tenant-a", "MAT-1", NOW.plusSeconds(3600),
                ZoneId.of("Asia/Shanghai"), DeadlineBasis.COURT_ORDER, "court-order-1", null, "", null);
        assertFalse(deadline.actionable());
        MatterDeadline verified = deadline.verify("lawyer-1", NOW);
        assertTrue(verified.actionable());
        assertEquals("lawyer-1", verified.verifiedBy());
    }

    @Test
    public void shouldRequireLawyerAndTraceableArchiveRecord() {
        LegalMatter matter = LegalMatter.rehydrate("m-archive", "tenant-a", "Archive test", "client-1",
                MatterStatus.SIGNED_OFF, 5, NOW);
        org.junit.Assert.assertThrows(SecurityException.class, () -> matter.transitionTo(MatterStatus.CLOSED,
                command("assistant", MatterActorRole.PARALEGAL, "delivered", "ARCH-1", 5)));
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> matter.transitionTo(MatterStatus.CLOSED,
                command("lawyer", MatterActorRole.LAWYER, "delivered", "", 5)));
        assertEquals(MatterStatus.SIGNED_OFF, matter.status());
        assertEquals(5, matter.version());
        matter.transitionTo(MatterStatus.CLOSED,
                command("lawyer", MatterActorRole.LAWYER, "delivery confirmed", "ARCH-1", 5));
        assertEquals(MatterStatus.CLOSED, matter.status());
        assertEquals("ARCH-1", matter.transitionHistory().get(0).reference());
        org.junit.Assert.assertThrows(IllegalStateException.class, () -> matter.transitionTo(MatterStatus.ANALYSIS,
                command("lawyer", MatterActorRole.LAWYER, "reopen", "ARCH-1", 6)));
    }

    private MatterTransitionCommand command(String actor, MatterActorRole role, String reason, String reference, long version) {
        return new MatterTransitionCommand(actor, role, reason, reference, version, NOW);
    }
}
