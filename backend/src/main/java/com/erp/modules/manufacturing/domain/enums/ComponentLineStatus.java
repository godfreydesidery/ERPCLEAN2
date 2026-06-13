package com.erp.modules.manufacturing.domain.enums;

/**
 * Status of a work-order component line (ADR-0035 D-3).
 */
public enum ComponentLineStatus {
    /** Planned from BOM explosion; not yet issued. */
    PLANNED,
    /** Partially issued (issued_qty > 0 but < planned_qty). */
    PARTIAL,
    /** Fully issued (issued_qty >= planned_qty). */
    ISSUED
}
