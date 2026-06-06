package com.erp.platform.audit;

/**
 * Catalogue of {@code action} string constants for {@code audit_log.action} (DATA-MODEL §1.11,
 * ADR-0004 D-6). Use these constants at every emit call site to prevent typos and ease
 * grep-ability. Not all actions are wired to emit calls yet — they are defined here so the full
 * catalogue is visible and searchable from day one.
 */
public final class AuditActions {

    private AuditActions() {
    }

    // -- User lifecycle -------------------------------------------------------
    public static final String USER_CREATE       = "USER.CREATE";
    public static final String USER_DISABLE      = "USER.DISABLE";
    public static final String USER_ENABLE       = "USER.ENABLE";
    public static final String USER_UNLOCK       = "USER.UNLOCK";
    public static final String USER_PASSWORD_SET = "USER.PASSWORD_SET";
    public static final String USER_UPDATE       = "USER.UPDATE";

    // -- Role management ------------------------------------------------------
    public static final String ROLE_GRANT           = "ROLE.GRANT";
    public static final String ROLE_REVOKE          = "ROLE.REVOKE";
    public static final String ROLE_CREATE          = "ROLE.CREATE";
    public static final String ROLE_PERMISSIONS_SET = "ROLE.PERMISSIONS_SET";

    // -- Branch assignment ----------------------------------------------------
    public static final String BRANCH_ASSIGN      = "BRANCH.ASSIGN";
    public static final String BRANCH_UNASSIGN    = "BRANCH.UNASSIGN";
    public static final String BRANCH_SET_DEFAULT = "BRANCH.SET_DEFAULT";

    // -- Authentication / lockout ---------------------------------------------
    public static final String LOGIN_SUCCESS = "LOGIN.SUCCESS";
    public static final String LOGIN_FAIL    = "LOGIN.FAIL";
    public static final String ACCOUNT_LOCKED = "ACCOUNT.LOCKED";

    // -- Root bypass (ADR-0004 D-9) -------------------------------------------
    public static final String ROOT_BYPASS = "ROOT.BYPASS";

    // -- Customer lifecycle (ADR-0006 D-12) -----------------------------------
    public static final String CUSTOMER_CREATE        = "CUSTOMER.CREATE";
    public static final String CUSTOMER_UPDATE        = "CUSTOMER.UPDATE";
    public static final String CUSTOMER_ARCHIVE       = "CUSTOMER.ARCHIVE";
    public static final String CUSTOMER_RESTORE       = "CUSTOMER.RESTORE";
    public static final String CUSTOMER_BRANCH_ADD    = "CUSTOMER.BRANCH.ADD";
    public static final String CUSTOMER_BRANCH_REMOVE = "CUSTOMER.BRANCH.REMOVE";

    // -- Supplier lifecycle ---------------------------------------------------
    public static final String SUPPLIER_CREATE        = "SUPPLIER.CREATE";
    public static final String SUPPLIER_UPDATE        = "SUPPLIER.UPDATE";
    public static final String SUPPLIER_ARCHIVE       = "SUPPLIER.ARCHIVE";
    public static final String SUPPLIER_RESTORE       = "SUPPLIER.RESTORE";
    public static final String SUPPLIER_BRANCH_ADD    = "SUPPLIER.BRANCH.ADD";
    public static final String SUPPLIER_BRANCH_REMOVE = "SUPPLIER.BRANCH.REMOVE";

    // -- Agent lifecycle ------------------------------------------------------
    public static final String AGENT_CREATE        = "AGENT.CREATE";
    public static final String AGENT_UPDATE        = "AGENT.UPDATE";
    public static final String AGENT_ARCHIVE       = "AGENT.ARCHIVE";
    public static final String AGENT_RESTORE       = "AGENT.RESTORE";
    public static final String AGENT_BRANCH_ADD    = "AGENT.BRANCH.ADD";
    public static final String AGENT_BRANCH_REMOVE = "AGENT.BRANCH.REMOVE";

    // -- OtherParty lifecycle -------------------------------------------------
    public static final String OTHERPARTY_CREATE        = "OTHERPARTY.CREATE";
    public static final String OTHERPARTY_UPDATE        = "OTHERPARTY.UPDATE";
    public static final String OTHERPARTY_ARCHIVE       = "OTHERPARTY.ARCHIVE";
    public static final String OTHERPARTY_RESTORE       = "OTHERPARTY.RESTORE";
    public static final String OTHERPARTY_BRANCH_ADD    = "OTHERPARTY.BRANCH.ADD";
    public static final String OTHERPARTY_BRANCH_REMOVE = "OTHERPARTY.BRANCH.REMOVE";
}
