package com.erp.modules.products.domain.enums;

/**
 * Lifecycle state of a BOM header (ADR-0026 D-2).
 *
 * <pre>
 * DRAFT ──activate──▶ ACTIVE ──supersede──▶ ARCHIVED  (terminal)
 *   │                   │
 *   │                   └──archive (decommission)──▶ ARCHIVED
 *   └──(delete or archive while DRAFT)
 * </pre>
 *
 * At most one ACTIVE version per parent at any moment (BR-BOM-04 — enforced by
 * {@code uq_bom_one_active} partial-unique index + service guard).
 */
public enum BomStatus {

    /** Freely editable; not yet effective. */
    DRAFT,

    /** Effective version; component set is frozen (BR-BOM-03). */
    ACTIVE,

    /** Terminal; retained for history. */
    ARCHIVED
}
