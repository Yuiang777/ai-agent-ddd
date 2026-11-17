package cn.apple.application.legal.matter;

import cn.apple.domain.legal.matter.*;
import cn.apple.domain.legal.matter.repository.LegalMatterRepository;
import java.util.*;
import java.util.function.Supplier;

/** Local, non-durable adapter. Commands are serialized with rollback and detached aggregate snapshots. */
public final class InMemoryLegalMatterRepository implements LegalMatterRepository {
    private final Map<Key, LegalMatter> matters = new HashMap<>();
    private final Map<Key, LegalReviewTask> reviews = new HashMap<>();
    private final Map<Key, List<MatterAuditEvent>> audits = new HashMap<>();
    private final Map<Key, List<MatterEvidence>> evidence = new HashMap<>();
    private final Map<Key, List<MatterDeadline>> deadlines = new HashMap<>();
    private int transactionDepth;
    private boolean rollbackOnly;

    @Override public synchronized <T> T inTransaction(Supplier<T> operation) {
        if (transactionDepth > 0) {
            transactionDepth++;
            try { return operation.get(); }
            catch (RuntimeException | Error error) { rollbackOnly = true; throw error; }
            finally { transactionDepth--; }
        }
        State before = new State(new HashMap<>(matters), new HashMap<>(reviews), copyLists(audits), copyLists(evidence), copyLists(deadlines));
        transactionDepth = 1; rollbackOnly = false;
        try {
            T result = operation.get();
            if (rollbackOnly) throw new IllegalStateException("MATTER_TRANSACTION_ROLLBACK_ONLY");
            return result;
        } catch (RuntimeException | Error error) {
            restore(matters, before.matters()); restore(reviews, before.reviews()); restore(audits, before.audits());
            restore(evidence, before.evidence()); restore(deadlines, before.deadlines());
            throw error;
        } finally { transactionDepth = 0; rollbackOnly = false; }
    }

    @Override public synchronized LegalMatter create(LegalMatter matter) {
        if (matters.putIfAbsent(key(matter.tenantId(), matter.id()), copy(matter)) != null) throw new IllegalStateException("MATTER_ALREADY_EXISTS");
        return copy(matter);
    }
    @Override public synchronized Optional<LegalMatter> find(String tenantId, String matterId) {
        return Optional.ofNullable(matters.get(key(tenantId, matterId))).map(this::copy);
    }
    @Override public synchronized List<LegalMatter> list(String tenantId) {
        String tenant = required(tenantId, "tenantId");
        return matters.values().stream().filter(item -> item.tenantId().equals(tenant))
                .sorted(Comparator.comparing(LegalMatter::updatedAt).reversed()).map(this::copy).toList();
    }
    @Override public synchronized LegalMatter save(LegalMatter matter, long expectedVersion) {
        Key key = key(matter.tenantId(), matter.id());
        LegalMatter current = matters.get(key);
        if (current == null) throw new IllegalStateException("MATTER_NOT_FOUND");
        if (current.version() != expectedVersion || matter.version() <= expectedVersion) throw new IllegalStateException("MATTER_VERSION_CONFLICT");
        matters.put(key, copy(matter)); return copy(matter);
    }
    @Override public synchronized void appendAudit(MatterAuditEvent event) {
        parent(event.tenantId(), event.matterId());
        audits.computeIfAbsent(key(event.tenantId(), event.matterId()), ignored -> new ArrayList<>()).add(event);
    }
    @Override public synchronized List<MatterAuditEvent> audit(String tenantId, String matterId) {
        return List.copyOf(audits.getOrDefault(key(tenantId, matterId), List.of()));
    }
    @Override public synchronized LegalReviewTask saveReviewTask(LegalReviewTask task) {
        parent(task.tenantId(), task.matterId());
        if (task.version() != 0) throw new IllegalArgumentException("REVIEW_EXPECTED_VERSION_REQUIRED");
        if (reviews.putIfAbsent(key(task.tenantId(), task.id()), copy(task)) != null) throw new IllegalStateException("REVIEW_ALREADY_EXISTS");
        return copy(task);
    }
    @Override public synchronized LegalReviewTask saveReviewTask(LegalReviewTask task, long expectedVersion) {
        parent(task.tenantId(), task.matterId());
        Key key = key(task.tenantId(), task.id());
        LegalReviewTask current = reviews.get(key);
        if (current == null || !current.matterId().equals(task.matterId()) || current.version() != expectedVersion || task.version() <= expectedVersion) throw new IllegalStateException("REVIEW_VERSION_CONFLICT");
        reviews.put(key, copy(task)); return copy(task);
    }
    @Override public synchronized Optional<LegalReviewTask> findReviewTask(String tenantId, String taskId) {
        return Optional.ofNullable(reviews.get(key(tenantId, taskId))).map(this::copy);
    }
    @Override public synchronized List<LegalReviewTask> reviewTasks(String tenantId, String matterId) {
        return reviewTasks(tenantId).stream().filter(task -> task.matterId().equals(required(matterId, "matterId"))).toList();
    }
    @Override public synchronized List<LegalReviewTask> reviewTasks(String tenantId) {
        String tenant = required(tenantId, "tenantId");
        return reviews.values().stream().filter(task -> task.tenantId().equals(tenant))
                .sorted(Comparator.comparing(LegalReviewTask::id)).map(this::copy).toList();
    }
    @Override public synchronized MatterEvidence saveEvidence(MatterEvidence item) {
        parent(item.tenantId(), item.matterId());
        List<MatterEvidence> versions = evidence.computeIfAbsent(key(item.tenantId(), item.matterId()), ignored -> new ArrayList<>());
        int latest = versions.stream().filter(value -> value.id().equals(item.id())).mapToInt(MatterEvidence::version).max().orElse(0);
        if (item.version() <= latest) {
            MatterEvidence existing = versions.stream().filter(value -> value.id().equals(item.id()) && value.version() == item.version()).findFirst().orElse(null);
            if (item.version() == latest && item.equals(existing)) return existing;
            throw new IllegalStateException("EVIDENCE_VERSION_CONFLICT");
        }
        versions.add(item); return item;
    }
    @Override public synchronized List<MatterEvidence> evidence(String tenantId, String matterId) {
        return evidence.getOrDefault(key(tenantId, matterId), List.of()).stream()
                .sorted(Comparator.comparing(MatterEvidence::id).thenComparingInt(MatterEvidence::version)).toList();
    }
    @Override public synchronized MatterDeadline saveDeadline(MatterDeadline item) {
        parent(item.tenantId(), item.matterId());
        List<MatterDeadline> values = deadlines.computeIfAbsent(key(item.tenantId(), item.matterId()), ignored -> new ArrayList<>());
        MatterDeadline previous = values.stream().filter(value -> value.id().equals(item.id())).findFirst().orElse(null);
        if (previous != null && !previous.equals(item)) {
            if (!previous.verify(item.verifiedBy(), item.verifiedAt()).equals(item)) throw new IllegalStateException("DEADLINE_UPDATE_CONFLICT");
        }
        values.removeIf(value -> value.id().equals(item.id())); values.add(item); return item;
    }
    @Override public synchronized List<MatterDeadline> deadlines(String tenantId, String matterId) {
        return deadlines.getOrDefault(key(tenantId, matterId), List.of()).stream().sorted(Comparator.comparing(MatterDeadline::id)).toList();
    }
    private void parent(String tenant, String id) {
        if (!matters.containsKey(key(tenant, id))) throw new IllegalArgumentException("MATTER_NOT_FOUND");
    }
    private LegalMatter copy(LegalMatter item) {
        return LegalMatter.restoreArtifacts(LegalMatter.rehydrate(item.id(), item.tenantId(), item.title(), item.clientReference(),
                item.attributes(), item.status(), item.version(), item.updatedAt(), item.transitionHistory()), item.artifacts());
    }
    private LegalReviewTask copy(LegalReviewTask item) {
        return LegalReviewTask.restoreBinding(LegalReviewTask.rehydrate(item.id(), item.tenantId(), item.matterId(), item.type(),
                item.status(), item.version(), item.reviewerId(), item.opinion(), item.decisionReference(), item.decidedAt()), item.artifactBinding());
    }
    private <T> Map<Key, List<T>> copyLists(Map<Key, List<T>> values) {
        Map<Key, List<T>> result = new HashMap<>(); values.forEach((key, items) -> result.put(key, new ArrayList<>(items))); return result;
    }
    private <T> void restore(Map<Key, T> target, Map<Key, T> snapshot) { target.clear(); target.putAll(snapshot); }
    private Key key(String tenant, String id) { return new Key(required(tenant, "tenantId"), required(id, "id")); }
    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required"); return value.trim();
    }
    private record Key(String tenant, String id) { }
    private record State(Map<Key, LegalMatter> matters, Map<Key, LegalReviewTask> reviews,
                         Map<Key, List<MatterAuditEvent>> audits, Map<Key, List<MatterEvidence>> evidence,
                         Map<Key, List<MatterDeadline>> deadlines) { }
}
