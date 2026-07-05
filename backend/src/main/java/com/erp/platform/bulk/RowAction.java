package com.erp.platform.bulk;

/**
 * What a row resolved to. In {@link ImportMode#VALIDATE} it is the action that WOULD run; in
 * {@link ImportMode#COMMIT} it is the action that DID run. {@code ERROR} means the row was rejected
 * (validate) or failed (commit) — see {@link RowOutcome#message()}.
 */
public enum RowAction {
    CREATE,
    UPDATE,
    /** A deliberate no-op (e.g. a price row left blank means "leave the price unchanged"). Not an error. */
    SKIP,
    ERROR
}
