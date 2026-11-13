package cn.apple.domain.legal.matter;

import java.time.Instant;

public record ReviewTaskDecision(String reviewerId,
                                 MatterActorRole reviewerRole,
                                 boolean approved,
                                 String opinion,
                                 String reference,
                                 Instant decidedAt) {
    public ReviewTaskDecision {
        reviewerId = reviewerId == null ? "" : reviewerId.trim();
        opinion = opinion == null ? "" : opinion.trim();
        reference = reference == null ? "" : reference.trim();
        decidedAt = decidedAt == null ? Instant.now() : decidedAt;
    }
}
