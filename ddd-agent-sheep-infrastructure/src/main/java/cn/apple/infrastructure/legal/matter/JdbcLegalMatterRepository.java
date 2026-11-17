package cn.apple.infrastructure.legal.matter;

import cn.apple.domain.legal.matter.*;
import cn.apple.domain.legal.matter.repository.LegalMatterRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** MySQL adapter. Application commands hold a parent row lock until commit. */
public final class JdbcLegalMatterRepository implements LegalMatterRepository {
    private final JdbcTemplate jdbc;
    private final DataSource source;
    private final TransactionTemplate transactions;
    private final MatterSnapshotCodec codec;

    public JdbcLegalMatterRepository(DataSource source, ObjectMapper mapper) {
        this.source = source;
        this.jdbc = new JdbcTemplate(source);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.transactions.setTimeout(30);
        this.codec = new MatterSnapshotCodec(mapper);
    }

    @Override public <T> T inTransaction(Supplier<T> operation) {
        return transactions.execute(status -> operation.get());
    }

    @Override public LegalMatter create(LegalMatter matter) {
        return inTransaction(() -> {
            jdbc.update("INSERT INTO legal_matter (tenant_id, matter_id, version, payload) VALUES (?,?,?,?)",
                    matter.tenantId(), matter.id(), matter.version(), codec.matter(matter));
            return matter;
        });
    }

    @Override public Optional<LegalMatter> find(String tenantId, String matterId) {
        String lock = TransactionSynchronizationManager.hasResource(source) ? " FOR UPDATE" : "";
        return jdbc.query("SELECT payload FROM legal_matter WHERE tenant_id=? AND matter_id=?" + lock,
                (rs, index) -> codec.matter(rs.getString(1)), required(tenantId), required(matterId)).stream().findFirst();
    }

    @Override public List<LegalMatter> list(String tenantId) {
        return jdbc.query("SELECT payload FROM legal_matter WHERE tenant_id=?",
                (rs, index) -> codec.matter(rs.getString(1)), required(tenantId)).stream()
                .sorted(Comparator.comparing(LegalMatter::updatedAt).reversed()).toList();
    }

    @Override public LegalMatter save(LegalMatter matter, long expectedVersion) {
        if (matter.version() != expectedVersion + 1) throw new IllegalStateException("MATTER_VERSION_CONFLICT");
        int changed = jdbc.update("UPDATE legal_matter SET version=?, payload=? WHERE tenant_id=? AND matter_id=? AND version=?",
                matter.version(), codec.matter(matter), matter.tenantId(), matter.id(), expectedVersion);
        if (changed != 1) throw new IllegalStateException("MATTER_VERSION_CONFLICT");
        return matter;
    }

    @Override public void appendAudit(MatterAuditEvent event) {
        inTransaction(() -> {
            parent(event.tenantId(), event.matterId());
            jdbc.update("INSERT INTO legal_matter_audit (tenant_id, matter_id, event_id, payload) VALUES (?,?,?,?)",
                    event.tenantId(), event.matterId(), event.eventId(), codec.write(event));
            return null;
        });
    }

    @Override public List<MatterAuditEvent> audit(String tenantId, String matterId) {
        return jdbc.query("SELECT payload FROM legal_matter_audit WHERE tenant_id=? AND matter_id=? ORDER BY sequence_id",
                (rs, index) -> codec.read(rs.getString(1), MatterAuditEvent.class), required(tenantId), required(matterId));
    }

    @Override public LegalReviewTask saveReviewTask(LegalReviewTask task) {
        if (task.version() != 0) throw new IllegalArgumentException("REVIEW_EXPECTED_VERSION_REQUIRED");
        return inTransaction(() -> {
            parent(task.tenantId(), task.matterId());
            jdbc.update("INSERT INTO legal_matter_review (tenant_id, task_id, matter_id, version, payload) VALUES (?,?,?,?,?)",
                    task.tenantId(), task.id(), task.matterId(), task.version(), codec.review(task));
            return task;
        });
    }

    @Override public LegalReviewTask saveReviewTask(LegalReviewTask task, long expectedVersion) {
        return inTransaction(() -> {
            parent(task.tenantId(), task.matterId());
            if (task.version() <= expectedVersion) throw new IllegalStateException("REVIEW_VERSION_CONFLICT");
            int changed = jdbc.update("UPDATE legal_matter_review SET version=?, payload=? WHERE tenant_id=? AND task_id=? AND matter_id=? AND version=?",
                    task.version(), codec.review(task), task.tenantId(), task.id(), task.matterId(), expectedVersion);
            if (changed != 1) throw new IllegalStateException("REVIEW_VERSION_CONFLICT");
            return task;
        });
    }

    @Override public Optional<LegalReviewTask> findReviewTask(String tenantId, String taskId) {
        return jdbc.query("SELECT payload FROM legal_matter_review WHERE tenant_id=? AND task_id=?",
                (rs, index) -> codec.review(rs.getString(1)), required(tenantId), required(taskId)).stream().findFirst();
    }

    @Override public List<LegalReviewTask> reviewTasks(String tenantId, String matterId) {
        return jdbc.query("SELECT payload FROM legal_matter_review WHERE tenant_id=? AND matter_id=? ORDER BY task_id",
                (rs, index) -> codec.review(rs.getString(1)), required(tenantId), required(matterId));
    }

    @Override public List<LegalReviewTask> reviewTasks(String tenantId) {
        return jdbc.query("SELECT payload FROM legal_matter_review WHERE tenant_id=? ORDER BY task_id",
                (rs, index) -> codec.review(rs.getString(1)), required(tenantId));
    }

    @Override public MatterEvidence saveEvidence(MatterEvidence item) {
        return inTransaction(() -> {
            parent(item.tenantId(), item.matterId());
            List<MatterEvidence> versions = evidence(item.tenantId(), item.matterId()).stream()
                    .filter(value -> value.id().equals(item.id())).toList();
            int latest = versions.stream().mapToInt(MatterEvidence::version).max().orElse(0);
            if (item.version() <= latest) {
                MatterEvidence existing = versions.stream().filter(value -> value.version() == item.version()).findFirst().orElse(null);
                if (item.version() == latest && item.equals(existing)) return existing;
                throw new IllegalStateException("EVIDENCE_VERSION_CONFLICT");
            }
            jdbc.update("INSERT INTO legal_matter_evidence (tenant_id, matter_id, evidence_id, version, payload) VALUES (?,?,?,?,?)",
                    item.tenantId(), item.matterId(), item.id(), item.version(), codec.write(item));
            return item;
        });
    }

    @Override public List<MatterEvidence> evidence(String tenantId, String matterId) {
        return jdbc.query("SELECT payload FROM legal_matter_evidence WHERE tenant_id=? AND matter_id=? ORDER BY evidence_id, version",
                (rs, index) -> codec.read(rs.getString(1), MatterEvidence.class), required(tenantId), required(matterId));
    }

    @Override public MatterDeadline saveDeadline(MatterDeadline item) {
        return inTransaction(() -> {
            parent(item.tenantId(), item.matterId());
            MatterDeadline previous = deadlines(item.tenantId(), item.matterId()).stream()
                    .filter(value -> value.id().equals(item.id())).findFirst().orElse(null);
            if (previous == null) {
                jdbc.update("INSERT INTO legal_matter_deadline (tenant_id, matter_id, deadline_id, payload) VALUES (?,?,?,?)",
                        item.tenantId(), item.matterId(), item.id(), codec.write(item));
            } else if (!previous.equals(item)) {
                if (!previous.verify(item.verifiedBy(), item.verifiedAt()).equals(item)) {
                    throw new IllegalStateException("DEADLINE_UPDATE_CONFLICT");
                }
                jdbc.update("UPDATE legal_matter_deadline SET payload=? WHERE tenant_id=? AND matter_id=? AND deadline_id=?",
                        codec.write(item), item.tenantId(), item.matterId(), item.id());
            }
            return item;
        });
    }

    @Override public List<MatterDeadline> deadlines(String tenantId, String matterId) {
        return jdbc.query("SELECT payload FROM legal_matter_deadline WHERE tenant_id=? AND matter_id=? ORDER BY deadline_id",
                (rs, index) -> codec.read(rs.getString(1), MatterDeadline.class), required(tenantId), required(matterId));
    }

    private void parent(String tenantId, String matterId) {
        if (find(tenantId, matterId).isEmpty()) throw new IllegalArgumentException("MATTER_NOT_FOUND");
    }
    private String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("tenant and identifier are required");
        return value.trim();
    }
}
