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
}
