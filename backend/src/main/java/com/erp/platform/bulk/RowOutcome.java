package com.erp.platform.bulk;

/**
 * The result of processing one {@link ImportRow}: which row, what happened, an optional business
 * reference (e.g. the resulting code) and a user-safe message (the rejection/failure reason on
 * {@link RowAction#ERROR}). Messages must stay user-safe — no internal detail (error-message
 * hygiene rule).
 */
public record RowOutcome(int rowNumber, RowAction action, String reference, String message) {

    public static RowOutcome create(int rowNumber, String reference) {
        return new RowOutcome(rowNumber, RowAction.CREATE, reference, null);
    }

    public static RowOutcome update(int rowNumber, String reference) {
        return new RowOutcome(rowNumber, RowAction.UPDATE, reference, null);
    }

    /** A deliberate no-op row (e.g. no new value supplied) — reported with a reason, not an error. */
    public static RowOutcome skip(int rowNumber, String reference, String message) {
        return new RowOutcome(rowNumber, RowAction.SKIP, reference, message);
    }

    public static RowOutcome error(int rowNumber, String message) {
        return new RowOutcome(rowNumber, RowAction.ERROR, null, message);
    }

    public boolean ok() {
        return action != RowAction.ERROR;
    }
}
