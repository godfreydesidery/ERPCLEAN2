package com.erp.modules.stock.domain.enums;

/**
 * Lot consumption order policy for lot-tracked products (ADR-0028 D-2/D-7, OQ-INVD-04).
 *
 * <p>FEFO is the v1 default. FIFO and MANUAL are reserved for future refinement.
 */
public enum LotConsumptionPolicy {

    /** First-Expiry-First-Out — oldest expiry consumed first; NULL expiry last (FR-INVD-21). */
    FEFO,

    /** First-In-First-Out — oldest lot (by id / creation) consumed first. Reserved for v2. */
    FIFO,

    /** Manual selection — caller explicitly picks the lot. Reserved for v2. */
    MANUAL
}
