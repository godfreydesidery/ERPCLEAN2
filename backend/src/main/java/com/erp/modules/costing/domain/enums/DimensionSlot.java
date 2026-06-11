package com.erp.modules.costing.domain.enums;

/**
 * The bounded set of user-defined dimension slots on {@code journal_lines} (ADR-0025 D-3).
 * Each slot maps 1:1 to a nullable {@code *_value_id} column on {@code journal_lines} alongside
 * the pre-existing {@code branch_id} analysis tag (which is NOT a slot — BR-CC-08).
 *
 * <p>v1 activates {@code COST_CENTRE} and {@code DEPARTMENT} (the two seeded built-in dimensions).
 * {@code DIMENSION_3} is reserved for the Projects module (§3.13); {@code DIMENSION_4} is a
 * future reserve. Activating a reserved slot is a {@code dimensions} INSERT + per-poster wiring,
 * no schema change (NFR-CC-06, OQ-CC-08).
 */
public enum DimensionSlot {
    COST_CENTRE,
    DEPARTMENT,
    DIMENSION_3,
    DIMENSION_4
}
