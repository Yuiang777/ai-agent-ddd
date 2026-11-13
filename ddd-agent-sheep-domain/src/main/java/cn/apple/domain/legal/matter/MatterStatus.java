package cn.apple.domain.legal.matter;

import java.util.EnumSet;
import java.util.Map;

/**
 * Canonical server-side matter lifecycle. UI labels must map to this enum and
 * must not mutate a matter status directly.
 */
public enum MatterStatus {
    INTAKE,
    CONFLICT_CHECK,
    ENGAGEMENT_PENDING,
    MATERIAL_PENDING,
    ANALYSIS,
    REVIEW_REQUIRED,
    SIGNOFF_REQUIRED,
    SIGNED_OFF,
    CLOSED,
    REJECTED;

    private static final Map<MatterStatus, EnumSet<MatterStatus>> TRANSITIONS = Map.of(
            INTAKE, EnumSet.of(CONFLICT_CHECK, MATERIAL_PENDING, REJECTED),
            CONFLICT_CHECK, EnumSet.of(ENGAGEMENT_PENDING, MATERIAL_PENDING, REJECTED),
            ENGAGEMENT_PENDING, EnumSet.of(MATERIAL_PENDING, REJECTED),
            MATERIAL_PENDING, EnumSet.of(ANALYSIS, REJECTED),
            ANALYSIS, EnumSet.of(REVIEW_REQUIRED, MATERIAL_PENDING),
            REVIEW_REQUIRED, EnumSet.of(ANALYSIS, SIGNOFF_REQUIRED, MATERIAL_PENDING),
            SIGNOFF_REQUIRED, EnumSet.of(REVIEW_REQUIRED, SIGNED_OFF),
            SIGNED_OFF, EnumSet.of(CLOSED),
            CLOSED, EnumSet.noneOf(MatterStatus.class),
            REJECTED, EnumSet.noneOf(MatterStatus.class)
    );

    public boolean canTransitionTo(MatterStatus target) {
        return target != null && TRANSITIONS.getOrDefault(this, EnumSet.noneOf(MatterStatus.class)).contains(target);
    }
}
