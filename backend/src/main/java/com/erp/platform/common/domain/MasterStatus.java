package com.erp.platform.common.domain;

/**
 * Lifecycle status for soft-deletable master data (PROJECT-CONVENTIONS §3.6). Stored as a
 * {@code VARCHAR(32)} string, not an ordinal, so the DB value is stable across enum reordering.
 */
public enum MasterStatus {
    ACTIVE,
    INACTIVE,
    ARCHIVED
}
