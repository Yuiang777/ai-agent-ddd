package cn.apple.domain.legal.matter.repository;

import cn.apple.domain.legal.matter.LegalMatter;
import cn.apple.domain.legal.matter.LegalReviewTask;
import cn.apple.domain.legal.matter.MatterAuditEvent;
import cn.apple.domain.legal.matter.MatterDeadline;
import cn.apple.domain.legal.matter.MatterEvidence;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for the legal matter aggregate. Implementations must
 * scope every read and write by tenant and enforce optimistic version checks.
 */
public interface LegalMatterRepository {
    /** SQL adapters join one transaction for the entire application command. */
    default <T> T inTransaction(java.util.function.Supplier<T> operation) {
        return operation.get();
    }

    LegalMatter create(LegalMatter matter);

    Optional<LegalMatter> find(String tenantId, String matterId);

    List<LegalMatter> list(String tenantId);

    LegalMatter save(LegalMatter matter, long expectedVersion);

    void appendAudit(MatterAuditEvent event);

    List<MatterAuditEvent> audit(String tenantId, String matterId);

    LegalReviewTask saveReviewTask(LegalReviewTask task);

    default LegalReviewTask saveReviewTask(LegalReviewTask task, long expectedVersion) {
        return saveReviewTask(task);
    }

    Optional<LegalReviewTask> findReviewTask(String tenantId, String taskId);

    List<LegalReviewTask> reviewTasks(String tenantId, String matterId);

    List<LegalReviewTask> reviewTasks(String tenantId);

    MatterEvidence saveEvidence(MatterEvidence evidence);

    List<MatterEvidence> evidence(String tenantId, String matterId);

    MatterDeadline saveDeadline(MatterDeadline deadline);

    List<MatterDeadline> deadlines(String tenantId, String matterId);
}
