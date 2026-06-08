package com.erp.modules.gl.domain.enums;

/**
 * Which side an account normally carries a positive balance on (BR-GL-12, ADR-0013 D-2a).
 * Derived from AccountType at write time (ASSET/EXPENSE → DEBIT; LIABILITY/EQUITY/INCOME → CREDIT)
 * and stored for read efficiency.
 */
public enum NormalBalance {
    DEBIT,
    CREDIT;

    /** Derives the normal balance from the account type per BR-GL-12. */
    public static NormalBalance forType(AccountType type) {
        return switch (type) {
            case ASSET, EXPENSE -> DEBIT;
            case LIABILITY, EQUITY, INCOME -> CREDIT;
        };
    }
}
