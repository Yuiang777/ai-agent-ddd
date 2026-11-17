package cn.apple.test.domain;

import cn.apple.application.legal.matter.InMemoryLegalMatterRepository;
import cn.apple.application.legal.matter.LegalMatterApplicationService;
import cn.apple.domain.legal.matter.*;
import org.junit.Test;
import java.time.Instant;
import java.util.Set;
import static org.junit.Assert.*;

public class InMemoryLegalMatterRepositoryTest {
    private MatterTransitionCommand command(long version) {
        return new MatterTransitionCommand("lawyer", MatterActorRole.LAWYER, "checked", "REF", version, Instant.now());
    }
    @Test public void readsAreDetachedAndStaleSavesAreRejected() {
        var repo = new InMemoryLegalMatterRepository();
        String id = repo.create(LegalMatter.open("t", "Matter", "C", Instant.now())).id();
        var left = repo.find("t", id).orElseThrow();
        var right = repo.find("t", id).orElseThrow();
        left.transitionTo(MatterStatus.MATERIAL_PENDING, command(0));
        assertEquals(0, repo.find("t", id).orElseThrow().version());
        repo.save(left, 0);
        right.transitionTo(MatterStatus.MATERIAL_PENDING, command(0));
        assertThrows(IllegalStateException.class, () -> repo.save(right, 0));
        assertEquals(1, repo.find("t", id).orElseThrow().version());
    }
    @Test public void failedTransactionRollsBackMatterEvidenceAndAudit() {
        var repo = new InMemoryLegalMatterRepository();
        var service = new LegalMatterApplicationService(repo);
        String id = service.create("t", "lawyer", "Matter", "C").matter().id();
        assertThrows(IllegalStateException.class, () -> repo.inTransaction(() -> {
            service.transition("t", id, MatterStatus.MATERIAL_PENDING, command(0));
            service.addEvidence("t", id, new LegalMatterApplicationService.EvidenceInput("E", 1, "objects/E", "a".repeat(64),
                    EvidenceSourceType.CLIENT_SUBMITTED, Instant.now(), "lawyer", EvidencePreservationStatus.UNVERIFIED, Set.of()));
            throw new IllegalStateException("simulated later failure");
        }));
        var view = service.get("t", id);
        assertEquals(MatterStatus.INTAKE, view.matter().status());
        assertEquals(0, view.matter().version());
        assertTrue(view.evidence().isEmpty());
        assertEquals(1, service.timeline("t", id).audit().size());
    }
    @Test public void invalidSignoffCannotPartiallyStartOrMutateReview() {
        var service = new LegalMatterApplicationService(new InMemoryLegalMatterRepository());
        String id = service.create("t", "lawyer", "Matter", "C").matter().id();
        service.transition("t", id, MatterStatus.MATERIAL_PENDING, command(0));
        service.transition("t", id, MatterStatus.ANALYSIS, command(1));
        service.publishArtifact("t", id, "Report", "RUN", command(2));
        var quality = service.submitForReview("t", id, "RUN", ReviewTaskType.QUALITY_REVIEW, command(3));
        service.decideReview("t", id, quality.reviewTasks().get(0).id(),
                new ReviewTaskDecision("reviewer", MatterActorRole.LAWYER, true, "checked", "REF", Instant.now()));
        var before = service.get("t", id);
        var submitted = service.submitForReview("t", id, "RUN", ReviewTaskType.LAWYER_SIGNOFF, command(before.matter().version()));
        var signoff = submitted.reviewTasks().stream().filter(task -> task.type() == ReviewTaskType.LAWYER_SIGNOFF).findFirst().orElseThrow();
        assertThrows(IllegalStateException.class, () -> service.submitForReview("t", id, "RUN", ReviewTaskType.LAWYER_SIGNOFF, command(submitted.matter().version())));
        assertThrows(IllegalArgumentException.class, () -> service.decideReview("t", id, signoff.id(),
                new ReviewTaskDecision("signer", MatterActorRole.LAWYER, true, "checked", "", Instant.now())));
        var after = service.get("t", id);
        var stored = after.reviewTasks().stream().filter(task -> task.id().equals(signoff.id())).findFirst().orElseThrow();
        assertEquals(ReviewTaskStatus.PENDING, stored.status());
        assertEquals(0, stored.version());
        assertEquals(MatterStatus.SIGNOFF_REQUIRED, after.matter().status());
    }
    @Test public void evidenceFingerprintChangesWhenPreservationChanges() {
        var service = new LegalMatterApplicationService(new InMemoryLegalMatterRepository());
        String id = service.create("t", "lawyer", "Matter", "C").matter().id();
        service.transition("t", id, MatterStatus.MATERIAL_PENDING, command(0));
        service.transition("t", id, MatterStatus.ANALYSIS, command(1));
        service.publishArtifact("t", id, "Report", "RUN", command(2));
        service.addEvidence("t", id, new LegalMatterApplicationService.EvidenceInput("E", 1, "objects/E", "a".repeat(64),
                EvidenceSourceType.CLIENT_SUBMITTED, Instant.now(), "lawyer", EvidencePreservationStatus.UNVERIFIED, Set.of()));
        var before = service.get("t", id).currentArtifactBinding();
        var after = service.preserveEvidence("t", id, "E", "lawyer").currentArtifactBinding();
        assertEquals(before.artifactVersion(), after.artifactVersion());
        assertNotEquals(before.evidenceFingerprint(), after.evidenceFingerprint());
    }
    @Test public void swallowedNestedFailureStillRollsBackOuterTransaction() {
        var repo = new InMemoryLegalMatterRepository();
        assertThrows(IllegalStateException.class, () -> repo.inTransaction(() -> {
            repo.create(LegalMatter.open("t", "Matter", "C", Instant.now()));
            try { repo.inTransaction(() -> { throw new IllegalArgumentException("nested"); }); }
            catch (IllegalArgumentException ignored) { }
            return null;
        }));
        assertTrue(repo.list("t").isEmpty());
    }
    @Test public void tenantAndIdentifierCannotAliasThroughColonConcatenation() {
        var repo = new InMemoryLegalMatterRepository();
        Instant now = Instant.now();
        repo.create(LegalMatter.rehydrate("b:c", "a", "One", "C", MatterStatus.INTAKE, 0, now));
        repo.create(LegalMatter.rehydrate("c", "a:b", "Two", "C", MatterStatus.INTAKE, 0, now));
        assertEquals("One", repo.find("a", "b:c").orElseThrow().title());
        assertEquals("Two", repo.find("a:b", "c").orElseThrow().title());
        assertEquals(1, repo.list("a").size());
    }
    @Test public void clientCannotActAsInternalReviewer() {
        var task = LegalReviewTask.create("R", "t", "M", ReviewTaskType.QUALITY_REVIEW, Instant.now());
        assertThrows(IllegalArgumentException.class, () -> task.start("client", MatterActorRole.CLIENT));
        assertEquals(ReviewTaskStatus.PENDING, task.status());
    }

    @Test public void repeatedPreservationDoesNotInvalidateCurrentReview() {
        var service = new LegalMatterApplicationService(new InMemoryLegalMatterRepository());
        String id = service.create("t", "lawyer", "Matter", "C").matter().id();
        service.transition("t", id, MatterStatus.MATERIAL_PENDING, command(0));
        service.transition("t", id, MatterStatus.ANALYSIS, command(1));
        service.publishArtifact("t", id, "Report", "RUN", command(2));
        service.addEvidence("t", id, new LegalMatterApplicationService.EvidenceInput("E", 1, "objects/E", "a".repeat(64),
                EvidenceSourceType.CLIENT_SUBMITTED, Instant.now(), "lawyer", EvidencePreservationStatus.UNVERIFIED, Set.of()));
        service.preserveEvidence("t", id, "E", "lawyer");
        var submitted = service.submitForReview("t", id, "RUN", ReviewTaskType.QUALITY_REVIEW,
                command(service.get("t", id).matter().version()));
        int auditBefore = service.timeline("t", id).audit().size();
        var retried = service.preserveEvidence("t", id, "E", "lawyer");
        assertEquals(MatterStatus.REVIEW_REQUIRED, retried.matter().status());
        assertEquals(submitted.currentArtifactBinding(), retried.currentArtifactBinding());
        assertEquals(submitted.evidence().size(), retried.evidence().size());
        assertEquals(auditBefore, service.timeline("t", id).audit().size());
        assertEquals(1, service.reviewQueue("t").size());
    }

    @Test public void exactEvidenceRegistrationRetryDoesNotResetReview() {
        var service = new LegalMatterApplicationService(new InMemoryLegalMatterRepository());
        String id = service.create("t", "lawyer", "Matter", "C").matter().id();
        service.transition("t", id, MatterStatus.MATERIAL_PENDING, command(0));
        service.transition("t", id, MatterStatus.ANALYSIS, command(1));
        service.publishArtifact("t", id, "Report", "RUN", command(2));
        var input = new LegalMatterApplicationService.EvidenceInput("E", 1, "objects/E", "a".repeat(64),
                EvidenceSourceType.CLIENT_SUBMITTED, Instant.now(), "lawyer", EvidencePreservationStatus.UNVERIFIED, Set.of());
        service.addEvidence("t", id, input);
        var submitted = service.submitForReview("t", id, "RUN", ReviewTaskType.QUALITY_REVIEW,
                command(service.get("t", id).matter().version()));
        int auditBefore = service.timeline("t", id).audit().size();
        var retry = service.addEvidence("t", id, input);
        assertEquals(MatterStatus.REVIEW_REQUIRED, retry.matter().status());
        assertEquals(submitted.matter().version(), retry.matter().version());
        assertEquals(submitted.currentArtifactBinding(), retry.currentArtifactBinding());
        assertEquals(auditBefore, service.timeline("t", id).audit().size());
        assertEquals(1, retry.evidence().size());
        var changed = new LegalMatterApplicationService.EvidenceInput("E", 1, "objects/E", "b".repeat(64),
                input.sourceType(), input.acquiredAt(), "lawyer", input.preservationStatus(), Set.of());
        assertThrows(IllegalStateException.class, () -> service.addEvidence("t", id, changed));
        assertEquals(MatterStatus.REVIEW_REQUIRED, service.get("t", id).matter().status());
    }

    @Test public void deadlineRegistrationRetryKeepsAuditAndVerificationHistory() {
        var service = new LegalMatterApplicationService(new InMemoryLegalMatterRepository());
        String id = service.create("t", "lawyer", "Matter", "C").matter().id();
        var input = new LegalMatterApplicationService.DeadlineInput("D", Instant.parse("2026-12-01T00:00:00Z"),
                java.time.ZoneId.of("Asia/Shanghai"), DeadlineBasis.COURT_ORDER, "COURT-REF", "lawyer", MatterActorRole.LAWYER);
        service.proposeDeadline("t", id, input);
        int before = service.timeline("t", id).audit().size();
        assertEquals(1, service.proposeDeadline("t", id, input).deadlines().size());
        assertEquals(before, service.timeline("t", id).audit().size());
        var verified = service.verifyDeadline("t", id, "D", "lawyer").deadlines().get(0);
        int after = service.timeline("t", id).audit().size();
        assertThrows(IllegalStateException.class, () -> service.proposeDeadline("t", id, input));
        assertEquals(verified, service.get("t", id).deadlines().get(0));
        assertEquals(after, service.timeline("t", id).audit().size());
    }

    @Test public void clientsCannotVerifyDeadlinesThroughApplicationService() {
        var service = new LegalMatterApplicationService(new InMemoryLegalMatterRepository());
        String id = service.create("t", "client", "Matter", "C").matter().id();
        var proposed = new LegalMatterApplicationService.DeadlineInput("D", Instant.parse("2026-12-01T00:00:00Z"),
                java.time.ZoneId.of("Asia/Shanghai"), DeadlineBasis.COURT_ORDER, "COURT-REF", "lawyer", MatterActorRole.LAWYER);
        service.proposeDeadline("t", id, proposed);
        int auditBefore = service.timeline("t", id).audit().size();
        assertThrows(SecurityException.class, () -> service.verifyDeadline("t", id, "D", "client", MatterActorRole.CLIENT, Instant.now()));
        assertEquals(DeadlineStatus.PROPOSED, service.get("t", id).deadlines().get(0).status());
        assertEquals(auditBefore, service.timeline("t", id).audit().size());
    }

    @Test public void clientsCannotChangeMatterLifecycleThroughApplicationService() {
        for (MatterStatus target : Set.of(MatterStatus.CONFLICT_CHECK, MatterStatus.MATERIAL_PENDING, MatterStatus.REJECTED)) {
            var service = new LegalMatterApplicationService(new InMemoryLegalMatterRepository());
            String id = service.create("t", "client", "Matter", "C").matter().id();
            var command = new MatterTransitionCommand("client", MatterActorRole.CLIENT, "client request", "", 0L, Instant.now());
            assertThrows(SecurityException.class, () -> service.transition("t", id, target, command));
            var unchanged = service.get("t", id);
            assertEquals(MatterStatus.INTAKE, unchanged.matter().status());
            assertEquals(0, unchanged.matter().version());
            assertEquals(1, service.timeline("t", id).audit().size());
        }
    }

    @Test public void nonStaffCannotStartOrDecideReviewsThroughApplicationService() {
        var service = new LegalMatterApplicationService(new InMemoryLegalMatterRepository());
        String id = service.create("t", "client", "Matter", "C").matter().id();
        service.transition("t", id, MatterStatus.MATERIAL_PENDING, command(0));
        service.transition("t", id, MatterStatus.ANALYSIS, command(1));
        service.publishArtifact("t", id, "Report", "RUN", command(2));
        var submitted = service.submitForReview("t", id, "RUN", ReviewTaskType.QUALITY_REVIEW, command(3));
        String taskId = submitted.reviewTasks().get(0).id();
        int auditBefore = service.timeline("t", id).audit().size();

        for (MatterActorRole role : new MatterActorRole[] {MatterActorRole.CLIENT, MatterActorRole.SYSTEM}) {
            String actor = role == MatterActorRole.CLIENT ? "client" : "system";
            assertThrows(SecurityException.class, () -> service.startReview("t", id, taskId, actor, role));
            assertThrows(SecurityException.class, () -> service.decideReview("t", id, taskId,
                    new ReviewTaskDecision(actor, role, false, "returned", "", Instant.now())));
        }

        var after = service.get("t", id);
        var task = after.reviewTasks().stream().filter(item -> item.id().equals(taskId)).findFirst().orElseThrow();
        assertEquals(MatterStatus.REVIEW_REQUIRED, after.matter().status());
        assertEquals(4, after.matter().version());
        assertEquals(ReviewTaskStatus.PENDING, task.status());
        assertEquals(0, task.version());
        assertEquals(auditBefore, service.timeline("t", id).audit().size());
    }

    @Test public void clientsCannotCreateReviewTasksThroughApplicationService() {
        var service = new LegalMatterApplicationService(new InMemoryLegalMatterRepository());
        String id = service.create("t", "client", "Matter", "C").matter().id();
        assertThrows(SecurityException.class, () -> service.createReviewTask("t", id, ReviewTaskType.QUALITY_REVIEW,
                "client", MatterActorRole.CLIENT, Instant.now()));
        assertTrue(service.get("t", id).reviewTasks().isEmpty());
        assertEquals(1, service.timeline("t", id).audit().size());
    }

    @Test public void clientsCannotProposeDeadlinesThroughApplicationService() {
        var service = new LegalMatterApplicationService(new InMemoryLegalMatterRepository());
        String id = service.create("t", "client", "Matter", "C").matter().id();
        var input = new LegalMatterApplicationService.DeadlineInput("D", Instant.now(), java.time.ZoneId.of("Asia/Shanghai"),
                DeadlineBasis.INTERNAL_TARGET, "internal", "client", MatterActorRole.CLIENT);
        assertThrows(SecurityException.class, () -> service.proposeDeadline("t", id, input));
        assertTrue(service.get("t", id).deadlines().isEmpty());
    }

}
