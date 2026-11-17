package cn.apple.infrastructure.legal;

import cn.apple.application.legal.matter.LegalMatterApplicationService;
import cn.apple.domain.legal.matter.*;
import cn.apple.infrastructure.legal.matter.JdbcLegalMatterRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.Assert.*;

public class JdbcLegalMatterRepositoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private JdbcDataSource source(String url) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(url);
        source.setUser("sa");
        return source;
    }
    private JdbcLegalMatterRepository repository(JdbcDataSource source) {
        return new JdbcLegalMatterRepository(source, new ObjectMapper().findAndRegisterModules());
    }
    private JdbcDataSource database() {
        var source = source("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/legal-matter-mysql.sql")).execute(source);
        return source;
    }
    private MatterTransitionCommand command(long version) {
        return new MatterTransitionCommand("lawyer", MatterActorRole.LAWYER, "checked", "REF", version, Instant.now());
    }

    @Test public void restartRestoresScopeHistoryReviewsEvidenceDeadlinesAndAudit() throws Exception {
        String url = "jdbc:h2:file:" + temporary.getRoot().toPath().resolve("matters").toString().replace('\\', '/') + ";MODE=MySQL";
        var firstSource = source(url);
        new ResourceDatabasePopulator(new ClassPathResource("db/legal-matter-mysql.sql")).execute(firstSource);
        var first = repository(firstSource);
        var service = new LegalMatterApplicationService(first);
        var created = service.create("tenant-a", "lawyer", "Contract", "C-1", Map.of(),
                new MatterScope("CONTRACT", List.of("CN", "US-NY"), List.of("en")));
        String id = created.matter().id();
        service.transition("tenant-a", id, MatterStatus.MATERIAL_PENDING, command(0));
        service.transition("tenant-a", id, MatterStatus.ANALYSIS, command(1));
        service.publishArtifact("tenant-a", id, "Report V1", "RUN-1", command(2));
        var submitted = service.submitForReview("tenant-a", id, "RUN-1", ReviewTaskType.QUALITY_REVIEW, command(3));
        assertEquals(MatterStatus.REVIEW_REQUIRED, submitted.matter().status());
        service.addEvidence("tenant-a", id, new LegalMatterApplicationService.EvidenceInput("E-1", 1,
                "objects/E-1", "a".repeat(64), EvidenceSourceType.CLIENT_SUBMITTED, Instant.now(), "lawyer",
                EvidencePreservationStatus.UNVERIFIED, Set.of("ISSUE-1")));
        service.proposeDeadline("tenant-a", id, new LegalMatterApplicationService.DeadlineInput("D-1",
                Instant.now().plusSeconds(3600), ZoneId.of("UTC"), DeadlineBasis.COURT_ORDER, "ORDER-1",
                "lawyer", MatterActorRole.LAWYER));
        service.verifyDeadline("tenant-a", id, "D-1", "lawyer");
        new JdbcTemplate(firstSource).execute("SHUTDOWN");
        var second = repository(source(url));
        var restored = new LegalMatterApplicationService(second).get("tenant-a", id);
        assertEquals(created.scope(), restored.scope());
        assertEquals(4, restored.matter().transitionHistory().size());
        assertEquals(1, restored.reviewTasks().size());
        assertEquals(Set.of("ISSUE-1"), restored.evidence().get(0).issueIds());
        assertTrue(restored.deadlines().get(0).actionable());
        assertFalse(second.audit("tenant-a", id).isEmpty());
        assertTrue(second.find("tenant-b", id).isEmpty());
        assertTrue(second.reviewTasks("tenant-b").isEmpty());
        assertTrue(second.evidence("tenant-b", id).isEmpty());
        assertTrue(second.audit("tenant-b", id).isEmpty());
        new JdbcTemplate(source(url)).execute("SHUTDOWN");
    }

    @Test public void detachedConcurrentSnapshotsRejectStaleWrite() {
        var source = database();
        var first = repository(source);
        var second = repository(source);
        var matter = first.create(LegalMatter.open("t", "Matter", "C", Instant.now()));
        var left = first.find("t", matter.id()).orElseThrow();
        var right = second.find("t", matter.id()).orElseThrow();
        left.transitionTo(MatterStatus.MATERIAL_PENDING, command(0));
        right.transitionTo(MatterStatus.MATERIAL_PENDING, command(0));
        first.save(left, 0);
        assertThrows(IllegalStateException.class, () -> second.save(right, 0));
        assertEquals(1, first.find("t", matter.id()).orElseThrow().version());
    }

    @Test public void auditFailureRollsBackApplicationCreate() {
        var source = database();
        new JdbcTemplate(source).execute("ALTER TABLE legal_matter_audit ADD CONSTRAINT reject_audit CHECK (event_id = 'never')");
        var service = new LegalMatterApplicationService(repository(source));
        assertThrows(RuntimeException.class, () -> service.create("t", "actor", "Matter", "C"));
        assertEquals(0, new JdbcTemplate(source).queryForObject("SELECT COUNT(*) FROM legal_matter", Integer.class).intValue());
    }

    @Test public void invalidTransitionRollsBackReviewDecisionAndAudit() {
        var source = database();
        var repository = repository(source);
        var service = new LegalMatterApplicationService(repository);
        var matter = service.create("t", "actor", "Matter", "C");
        var task = service.createReviewTask("t", matter.matter().id(), ReviewTaskType.QUALITY_REVIEW,
                "lawyer", MatterActorRole.LAWYER, Instant.now());
        int before = repository.audit("t", matter.matter().id()).size();
        assertThrows(IllegalStateException.class, () -> service.decideReview("t", matter.matter().id(), task.id(),
                new ReviewTaskDecision("lawyer", MatterActorRole.LAWYER, true, "checked", "R", Instant.now())));
        var restored = repository.findReviewTask("t", task.id()).orElseThrow();
        assertEquals(ReviewTaskStatus.PENDING, restored.status());
        assertEquals(0, restored.version());
        assertEquals(before, repository.audit("t", matter.matter().id()).size());
    }

    @Test public void successfulDecisionReturnsFreshMatterAndRejectsReplay() {
        var repository = repository(database());
        var service = new LegalMatterApplicationService(repository);
        String id = service.create("t", "actor", "Matter", "C").matter().id();
        service.transition("t", id, MatterStatus.MATERIAL_PENDING, command(0));
        service.transition("t", id, MatterStatus.ANALYSIS, command(1));
        service.publishArtifact("t", id, "Report V1", "RUN", command(2));
        var review = service.submitForReview("t", id, "RUN", ReviewTaskType.QUALITY_REVIEW, command(3));
        String taskId = review.reviewTasks().get(0).id();
        var decision = new ReviewTaskDecision("reviewer", MatterActorRole.LAWYER, true, "checked", "R", Instant.now());
        var result = service.decideReview("t", id, taskId, decision);
        assertEquals(MatterStatus.SIGNOFF_REQUIRED, result.matter().status());
        assertEquals(ReviewTaskStatus.COMPLETED, result.reviewTasks().get(0).status());
        assertThrows(IllegalStateException.class, () -> service.decideReview("t", id, taskId, decision));
        assertEquals(5, service.get("t", id).matter().version());
    }
    @Test public void simultaneousCommandsCommitOnlyOneExpectedVersion() throws Exception {
        var source = database();
        var first = new LegalMatterApplicationService(repository(source));
        var second = new LegalMatterApplicationService(repository(source));
        String id = first.create("t", "actor", "Matter", "C").matter().id();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> left = () -> {
                start.await();
                try { first.transition("t", id, MatterStatus.MATERIAL_PENDING, command(0)); return true; }
                catch (IllegalStateException conflict) { return false; }
            };
            java.util.concurrent.Callable<Boolean> right = () -> {
                start.await();
                try { second.transition("t", id, MatterStatus.MATERIAL_PENDING, command(0)); return true; }
                catch (IllegalStateException conflict) { return false; }
            };
            var a = executor.submit(left);
            var b = executor.submit(right);
            start.countDown();
            assertNotEquals(a.get(10, java.util.concurrent.TimeUnit.SECONDS), b.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, first.get("t", id).matter().version());
            assertEquals(2, repository(source).audit("t", id).size());
        } finally { executor.shutdownNow(); }
    }

    @Test public void simultaneousReviewSubmissionsCreateOnlyOneTaskAndAudit() throws Exception {
        var source = database();
        var first = new LegalMatterApplicationService(repository(source));
        var second = new LegalMatterApplicationService(repository(source));
        String id = analysis(first);
        long expected = first.get("t", id).matter().version();
        int auditBefore = repository(source).audit("t", id).size();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var left = executor.submit((java.util.concurrent.Callable<Boolean>) () -> {
                start.await();
                try { first.submitForReview("t", id, "RUN-A", ReviewTaskType.QUALITY_REVIEW, command(expected)); return true; }
                catch (IllegalStateException conflict) { return false; }
            });
            var right = executor.submit((java.util.concurrent.Callable<Boolean>) () -> {
                start.await();
                try { second.submitForReview("t", id, "RUN-B", ReviewTaskType.QUALITY_REVIEW, command(expected)); return true; }
                catch (IllegalStateException conflict) { return false; }
            });
            start.countDown();
            boolean leftWon = left.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(leftWon, right.get(10, java.util.concurrent.TimeUnit.SECONDS));
            var result = first.get("t", id);
            assertEquals(MatterStatus.REVIEW_REQUIRED, result.matter().status());
            assertEquals(expected + 1, result.matter().version());
            assertEquals(1, result.reviewTasks().stream().filter(task -> task.type() == ReviewTaskType.QUALITY_REVIEW
                    && task.status() == ReviewTaskStatus.PENDING).count());
            assertEquals(auditBefore + 2, repository(source).audit("t", id).size());
            assertEquals(1, repository(source).audit("t", id).stream()
                    .filter(event -> event.eventType().equals("REVIEW_TASK_CREATED")).count());
        } finally { executor.shutdownNow(); }
    }

    @Test public void concurrentReviewDecisionsCommitOnlyOneDecisionAndAudit() throws Exception {
        var source = database();
        var first = new LegalMatterApplicationService(repository(source));
        var second = new LegalMatterApplicationService(repository(source));
        String id = analysis(first);
        String task = submit(first, id, ReviewTaskType.QUALITY_REVIEW);
        int auditBefore = repository(source).audit("t", id).size();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> approve = () -> {
                start.await();
                try { first.decideReview("t", id, task, approval()); return true; }
                catch (IllegalStateException | SecurityException conflict) { return false; }
            };
            java.util.concurrent.Callable<Boolean> reject = () -> {
                start.await();
                try {
                    second.decideReview("t", id, task, new ReviewTaskDecision("other-reviewer",
                            MatterActorRole.LAWYER, false, "needs more material", "RETURN", Instant.now()));
                    return true;
                } catch (IllegalStateException | SecurityException conflict) { return false; }
            };
            var left = executor.submit(approve);
            var right = executor.submit(reject);
            start.countDown();
            boolean approved = left.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(approved, right.get(10, java.util.concurrent.TimeUnit.SECONDS));
            var result = first.get("t", id);
            assertEquals(approved ? MatterStatus.SIGNOFF_REQUIRED : MatterStatus.MATERIAL_PENDING, result.matter().status());
            var stored = result.reviewTasks().stream().filter(item -> item.id().equals(task)).findFirst().orElseThrow();
            assertEquals(approved ? ReviewTaskStatus.COMPLETED : ReviewTaskStatus.RETURNED, stored.status());
            assertEquals(2, stored.version());
            assertEquals(1, repository(source).audit("t", id).stream().filter(event -> event.eventType().equals("REVIEW_DECIDED")).count());
            assertEquals(auditBefore + 2, repository(source).audit("t", id).size());
        } finally { executor.shutdownNow(); }
    }

    @Test public void simultaneousArtifactPublicationsKeepOneVersionAndOneAudit() throws Exception {
        var source = database();
        var first = new LegalMatterApplicationService(repository(source));
        var second = new LegalMatterApplicationService(repository(source));
        String id = analysis(first);
        long expected = first.get("t", id).matter().version();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var left = executor.submit((java.util.concurrent.Callable<Boolean>) () -> {
                start.await();
                try { first.publishArtifact("t", id, "Concurrent A", "A", command(expected)); return true; }
                catch (IllegalStateException conflict) { return false; }
            });
            var right = executor.submit((java.util.concurrent.Callable<Boolean>) () -> {
                start.await();
                try { second.publishArtifact("t", id, "Concurrent B", "B", command(expected)); return true; }
                catch (IllegalStateException conflict) { return false; }
            });
            start.countDown();
            boolean won = left.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(won, right.get(10, java.util.concurrent.TimeUnit.SECONDS));
            var matter = first.get("t", id).matter();
            assertEquals(expected + 1, matter.version());
            assertEquals(2, matter.artifacts().size());
            assertEquals(won ? "Concurrent A" : "Concurrent B", matter.artifacts().get(1).content());
            assertEquals(2, repository(source).audit("t", id).stream()
                    .filter(event -> event.eventType().equals("ARTIFACT_PUBLISHED")).count());
        } finally { executor.shutdownNow(); }
    }

    @Test public void concurrentDeadlineVerificationKeepsOneReviewerAndAudit() throws Exception {
        var source = database();
        var first = new LegalMatterApplicationService(repository(source));
        var second = new LegalMatterApplicationService(repository(source));
        String id = first.create("t", "lawyer", "Matter", "C").matter().id();
        first.proposeDeadline("t", id, new LegalMatterApplicationService.DeadlineInput("D",
                Instant.parse("2026-12-01T00:00:00Z"), ZoneId.of("Asia/Shanghai"), DeadlineBasis.COURT_ORDER,
                "COURT-REF", "lawyer", MatterActorRole.LAWYER));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        Instant leftTime = Instant.parse("2026-10-16T04:00:00Z");
        Instant rightTime = leftTime.plusSeconds(1);
        try {
            var left = executor.submit((java.util.concurrent.Callable<Boolean>) () -> {
                start.await();
                try { first.verifyDeadline("t", id, "D", "left-lawyer", MatterActorRole.LAWYER, leftTime); return true; }
                catch (IllegalStateException conflict) { return false; }
            });
            var right = executor.submit((java.util.concurrent.Callable<Boolean>) () -> {
                start.await();
                try { second.verifyDeadline("t", id, "D", "right-lawyer", MatterActorRole.LAWYER, rightTime); return true; }
                catch (IllegalStateException conflict) { return false; }
            });
            start.countDown();
            boolean leftWon = left.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(leftWon, right.get(10, java.util.concurrent.TimeUnit.SECONDS));
            var deadline = first.get("t", id).deadlines().get(0);
            assertEquals(DeadlineStatus.VERIFIED, deadline.status());
            assertEquals(leftWon ? "left-lawyer" : "right-lawyer", deadline.verifiedBy());
            assertEquals(leftWon ? leftTime : rightTime, deadline.verifiedAt());
            assertEquals(1, repository(source).audit("t", id).stream()
                    .filter(event -> event.eventType().equals("DEADLINE_VERIFIED")).count());
        } finally { executor.shutdownNow(); }
    }

    @Test public void staleReviewAndAlteredEvidenceVersionsAreRejected() {
        var source = database();
        var repository = repository(source);
        String id = repository.create(LegalMatter.open("t", "Matter", "C", Instant.now())).id();
        var task = LegalReviewTask.create("R", "t", id, ReviewTaskType.QUALITY_REVIEW, Instant.now());
        repository.saveReviewTask(task);
        var left = repository.findReviewTask("t", "R").orElseThrow();
        var right = repository.findReviewTask("t", "R").orElseThrow();
        left.start("lawyer", MatterActorRole.LAWYER);
        right.start("lawyer", MatterActorRole.LAWYER);
        repository.saveReviewTask(left, 0);
        assertThrows(IllegalStateException.class, () -> repository.saveReviewTask(right, 0));
        var evidence = new MatterEvidence("E", "t", id, 1, "objects/E", "a".repeat(64),
                EvidenceSourceType.CLIENT_SUBMITTED, Instant.now(), "lawyer", EvidencePreservationStatus.UNVERIFIED, Set.of());
        repository.saveEvidence(evidence);
        repository.saveEvidence(evidence);
        assertEquals(1, repository.evidence("t", id).size());
        var changed = new MatterEvidence("E", "t", id, 1, "objects/E", "b".repeat(64),
                evidence.sourceType(), evidence.acquiredAt(), "lawyer", evidence.preservationStatus(), Set.of());
        assertThrows(IllegalStateException.class, () -> repository.saveEvidence(changed));
        var foreign = new MatterEvidence("E", "other", id, 1, "objects/E", "a".repeat(64),
                evidence.sourceType(), evidence.acquiredAt(), "lawyer", evidence.preservationStatus(), Set.of());
        assertThrows(IllegalArgumentException.class, () -> repository.saveEvidence(foreign));
    }

    @Test public void sqlConfigurationChecksSchemaAndRegistersOneRepository() {
        var source = database();
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "app.legal.matter-store=sql", "app.legal.matter-schema-initialize=false");
            context.registerBean("mysqlDataSource", javax.sql.DataSource.class, () -> source);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
            context.register(cn.apple.config.SqlLegalMatterStoreConfig.class,
                    cn.apple.application.legal.matter.LegalMatterStoreConfig.class);
            context.refresh();
            assertEquals(1, context.getBeansOfType(cn.apple.domain.legal.matter.repository.LegalMatterRepository.class).size());
            assertTrue(context.getBean(cn.apple.domain.legal.matter.repository.LegalMatterRepository.class)
                    instanceof JdbcLegalMatterRepository);
        }
        assertThrows(RuntimeException.class, () -> new cn.apple.config.SqlLegalMatterStoreConfig()
                .sqlLegalMatterRepository(source("jdbc:h2:mem:" + UUID.randomUUID()), new ObjectMapper(), false));
    }

    private String analysis(LegalMatterApplicationService service) {
        String id = service.create("t", "lawyer", "Matter", "C").matter().id();
        service.transition("t", id, MatterStatus.MATERIAL_PENDING, command(0));
        service.transition("t", id, MatterStatus.ANALYSIS, command(1));
        service.publishArtifact("t", id, "Report one", "RUN", command(2));
        return id;
    }
    private String submit(LegalMatterApplicationService service, String id, ReviewTaskType type) {
        var view = service.submitForReview("t", id, "RUN", type, command(service.get("t", id).matter().version()));
        return view.reviewTasks().stream().filter(task -> task.type() == type && task.status() == ReviewTaskStatus.PENDING
                        && view.currentArtifactBinding().equals(task.artifactBinding()))
                .reduce((a,b) -> b).orElseThrow().id();
    }
    private ReviewTaskDecision approval() {
        return new ReviewTaskDecision("reviewer", MatterActorRole.LAWYER, true, "checked exact version", "SIGNED-REF", Instant.now());
    }

    @Test public void newReportInvalidatesPendingReviewAndBlocksDirectSignoff() {
        var service = new LegalMatterApplicationService(repository(database()));
        String id = analysis(service);
        String task = submit(service, id, ReviewTaskType.QUALITY_REVIEW);
        var before = service.get("t", id);
        service.publishArtifact("t", id, "Report two", "RUN2", command(before.matter().version()));
        assertEquals(MatterStatus.ANALYSIS, service.get("t", id).matter().status());
        assertEquals(2, service.get("t", id).matter().artifacts().size());
        assertThrows(IllegalStateException.class, () -> service.decideReview("t", id, task, approval()));
        assertThrows(SecurityException.class, () -> service.transition("t", id, MatterStatus.SIGNED_OFF,
                command(service.get("t", id).matter().version())));
        assertThrows(IllegalStateException.class, () -> submit(service, id, ReviewTaskType.LAWYER_SIGNOFF));
    }

    @Test public void evidenceChangeInvalidatesCompletedQualityReview() {
        var service = new LegalMatterApplicationService(repository(database()));
        String id = analysis(service);
        String task = submit(service, id, ReviewTaskType.QUALITY_REVIEW);
        service.decideReview("t", id, task, approval());
        service.addEvidence("t", id, new LegalMatterApplicationService.EvidenceInput("NEW", 1, "objects/NEW",
                "a".repeat(64), EvidenceSourceType.CLIENT_SUBMITTED, Instant.now(), "lawyer",
                EvidencePreservationStatus.UNVERIFIED, Set.of()));
        assertEquals(MatterStatus.ANALYSIS, service.get("t", id).matter().status());
        assertThrows(IllegalStateException.class, () -> submit(service, id, ReviewTaskType.LAWYER_SIGNOFF));
    }

    @Test public void signoffRequiresCurrentQualityReviewAndSurvivesReload() {
        var source = database();
        var service = new LegalMatterApplicationService(repository(source));
        String id = analysis(service);
        assertThrows(IllegalStateException.class, () -> submit(service, id, ReviewTaskType.LAWYER_SIGNOFF));
        service.decideReview("t", id, submit(service, id, ReviewTaskType.QUALITY_REVIEW), approval());
        String signoff = submit(service, id, ReviewTaskType.LAWYER_SIGNOFF);
        var restored = new LegalMatterApplicationService(repository(source));
        var result = restored.decideReview("t", id, signoff, new ReviewTaskDecision("signer", MatterActorRole.LAWYER, true, "checked", "REF", Instant.now()));
        assertEquals(MatterStatus.SIGNED_OFF, result.matter().status());
        assertEquals(MatterArtifact.digest("Report one"), result.matter().artifacts().get(0).sha256());
        assertTrue(result.reviewTasks().stream().allMatch(task -> task.artifactBinding() != null));
        assertThrows(IllegalStateException.class, () -> restored.publishArtifact("t", id, "Replacement", "RUN",
                command(result.matter().version())));
    }

    @Test public void authorAndQualityReviewerCannotSignTheirOwnReport() {
        var service = new LegalMatterApplicationService(repository(database()));
        String id = analysis(service);
        String review = submit(service, id, ReviewTaskType.QUALITY_REVIEW);
        assertThrows(SecurityException.class, () -> service.startReview("t", id, review, "lawyer", MatterActorRole.LAWYER));
        assertThrows(SecurityException.class, () -> service.decideReview("t", id, review,
                new ReviewTaskDecision("lawyer", MatterActorRole.LAWYER, true, "checked", "REF", Instant.now())));
        assertEquals(ReviewTaskStatus.PENDING, service.get("t", id).reviewTasks().get(0).status());
        service.startReview("t", id, review, "reviewer", MatterActorRole.LAWYER);
        assertThrows(SecurityException.class, () -> service.decideReview("t", id, review,
                new ReviewTaskDecision("other", MatterActorRole.LAWYER, true, "checked", "REF", Instant.now())));
        service.decideReview("t", id, review, approval());
        String signoff = submit(service, id, ReviewTaskType.LAWYER_SIGNOFF);
        assertThrows(SecurityException.class, () -> service.startReview("t", id, signoff, "reviewer", MatterActorRole.LAWYER));
        assertThrows(SecurityException.class, () -> service.decideReview("t", id, signoff, approval()));
        assertThrows(SecurityException.class, () -> service.decideReview("t", id, signoff,
                new ReviewTaskDecision("lawyer", MatterActorRole.LAWYER, true, "checked", "REF", Instant.now())));
        assertEquals(MatterStatus.SIGNOFF_REQUIRED, service.get("t", id).matter().status());
        assertEquals(MatterStatus.SIGNED_OFF, service.decideReview("t", id, signoff,
                new ReviewTaskDecision("signer", MatterActorRole.LAWYER, true, "checked", "REF", Instant.now())).matter().status());
    }

    @Test public void reviewCannotBeClaimedOutsideItsRequiredStage() {
        var repo = repository(database());
        var service = new LegalMatterApplicationService(repo);
        String id = analysis(service);
        String task = submit(service, id, ReviewTaskType.QUALITY_REVIEW);
        var matter = repo.find("t", id).orElseThrow();
        var restored = LegalMatter.rehydrate(matter.id(), matter.tenantId(), matter.title(), matter.clientReference(),
                MatterStatus.ANALYSIS, matter.version() + 1, matter.updatedAt());
        // A stage-only legacy snapshot lacks report bindings and cannot grant a claim.
        repo.save(restored, matter.version());
        assertThrows(IllegalStateException.class, () -> service.startReview("t", id, task, "reviewer", MatterActorRole.LAWYER));
        var stored = repo.findReviewTask("t", task).orElseThrow();
        assertEquals(ReviewTaskStatus.PENDING, stored.status());
        assertEquals(0, stored.version());
    }

    @Test public void queueContainsOnlyCurrentActionableTasksWhileHistoryRemains() {
        var service = new LegalMatterApplicationService(repository(database()));
        String id = analysis(service);
        assertTrue(service.reviewQueue("t").isEmpty());
        String first = submit(service, id, ReviewTaskType.QUALITY_REVIEW);
        assertEquals(first, service.reviewQueue("t").get(0).id());
        service.publishArtifact("t", id, "Replacement", "RUN2", command(service.get("t", id).matter().version()));
        assertTrue(service.reviewQueue("t").isEmpty());
        String current = submit(service, id, ReviewTaskType.QUALITY_REVIEW);
        assertEquals(1, service.reviewQueue("t").size());
        assertEquals(current, service.reviewQueue("t").get(0).id());
        service.decideReview("t", id, current, approval());
        assertTrue(service.reviewQueue("t").isEmpty());
        String signoff = submit(service, id, ReviewTaskType.LAWYER_SIGNOFF);
        assertEquals(signoff, service.reviewQueue("t").get(0).id());
        service.decideReview("t", id, signoff, new ReviewTaskDecision("signer", MatterActorRole.LAWYER,
                true, "checked", "REF", Instant.now()));
        assertTrue(service.reviewQueue("t").isEmpty());
        assertEquals(3, service.get("t", id).reviewTasks().size());
        assertTrue(service.reviewQueue("another-tenant").isEmpty());
    }

    @Test public void legacyUnboundReviewCannotApproveAnArtifact() {
        var service = new LegalMatterApplicationService(repository(database()));
        String id = analysis(service);
        var task = service.createReviewTask("t", id, ReviewTaskType.QUALITY_REVIEW, "lawyer", MatterActorRole.LAWYER, Instant.now());
        assertThrows(IllegalStateException.class, () -> service.decideReview("t", id, task.id(), approval()));
    }

    @Test public void versionOneSnapshotsRemainReadableWithoutGrantingApproval() {
        var source = database();
        var repo = repository(source);
        String id = repo.create(LegalMatter.open("t", "Legacy", "C", Instant.now())).id();
        repo.saveReviewTask(LegalReviewTask.create("LEGACY", "t", id, ReviewTaskType.QUALITY_REVIEW, Instant.now()));
        var jdbc = new JdbcTemplate(source);
        String matterJson = jdbc.queryForObject("SELECT payload FROM legal_matter WHERE tenant_id='t' AND matter_id=?", String.class, id);
        String reviewJson = jdbc.queryForObject("SELECT payload FROM legal_matter_review WHERE task_id='LEGACY'", String.class);
        try {
            var mapper = new ObjectMapper();
            var matterNode = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(matterJson);
            matterNode.put("schemaVersion", 1); matterNode.remove("artifacts");
            var reviewNode = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(reviewJson);
            reviewNode.put("schemaVersion", 1); reviewNode.remove("artifactBinding");
            jdbc.update("UPDATE legal_matter SET payload=? WHERE tenant_id='t' AND matter_id=?", mapper.writeValueAsString(matterNode), id);
            jdbc.update("UPDATE legal_matter_review SET payload=? WHERE task_id='LEGACY'", mapper.writeValueAsString(reviewNode));
        } catch (Exception error) { throw new AssertionError(error); }
        assertTrue(repo.find("t", id).orElseThrow().artifacts().isEmpty());
        assertNull(repo.findReviewTask("t", "LEGACY").orElseThrow().artifactBinding());
    }

}
