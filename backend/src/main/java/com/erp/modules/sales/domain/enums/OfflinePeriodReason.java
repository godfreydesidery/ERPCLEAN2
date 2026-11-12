package com.erp.modules.sales.domain.enums;

/** Why a till went offline (ADR-0068 D-1). */
public enum OfflinePeriodReason {
    /** The till's own check could not reach the server; any signed-in cashier may switch. */
    UNREACHABLE,
    /** Switched by hand on a reachable but unusable connection; needs POS.OFFLINE.SWITCH. */
    MANUAL
}
