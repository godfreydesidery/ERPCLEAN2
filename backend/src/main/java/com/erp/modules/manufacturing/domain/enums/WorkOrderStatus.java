package com.erp.modules.manufacturing.domain.enums;

/**
 * Work-order lifecycle states (ADR-0035 D-2).
 *
 * <pre>
 * PLANNED ──release──▶ RELEASED ──issue(first)──▶ IN_PROGRESS ──complete──▶ COMPLETED ──close──▶ CLOSED (terminal)
 *    │                    │                             │                        │
 *    └──── cancel ────────┴──────── cancel ─────────────┴──── cancel ────────────┘  (CANCELLED, terminal)
 * </pre>
 */
public enum WorkOrderStatus {
    /** Created; freely editable; no BOM pinned, no GL effect. */
    PLANNED,
    /** BOM exploded; component plan materialised; no GL effect yet. */
    RELEASED,
    /** First component issue posted; WIP accumulating. */
    IN_PROGRESS,
    /** Finished goods received at computed cost; WIP partially / fully relieved. */
    COMPLETED,
    /** Residual WIP cleared to Manufacturing Variance; terminal. */
    CLOSED,
    /** All posted stock movements reversed at original cost; terminal. */
    CANCELLED
}
