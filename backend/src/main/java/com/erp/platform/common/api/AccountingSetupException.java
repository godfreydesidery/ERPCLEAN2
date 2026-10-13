package com.erp.platform.common.api;

/**
 * The books cannot take a posting because of how the accounts are set up — no fiscal period for
 * the date, the period is closed, or a posting role has no usable account (ACC-21).
 *
 * <p>Carries two messages, because two very different people trip over it:
 * <ul>
 *   <li>{@link #getMessage()} — the accountant's version: names the period or the posting role and
 *       says what to change. Shown on the accounting screens (manual journals, period and year-end
 *       work, VAT, depreciation, revaluation, payroll posting) and always written to the log.</li>
 *   <li>{@link #userMessage()} — the operator's version: a cashier taking a receipt or a storekeeper
 *       adjusting stock cannot act on "posting role" or "fiscal period", so they are told plainly that
 *       the accounts are not ready and to ask the accountant.</li>
 * </ul>
 * {@link GlobalExceptionHandler} chooses between them by the request path.
 *
 * <p>Extends {@link IllegalStateException} on purpose: every existing caller that already catches
 * {@code IllegalStateException} around a GL post (the outbox posters, the POS payout path, the
 * direct-receipt restatement) keeps working unchanged.
 */
public class AccountingSetupException extends IllegalStateException {

    /** The operator-facing sentence used when nothing more specific applies. */
    public static final String DEFAULT_USER_MESSAGE =
            "Accounts are not set up for this date or transaction. Ask your accountant.";

    private final String userMessage;

    /**
     * @param accountantMessage the accountant-facing explanation (also logged); must be user-safe —
     *                          no ids, uids or internal codes
     */
    public AccountingSetupException(String accountantMessage) {
        this(accountantMessage, DEFAULT_USER_MESSAGE);
    }

    /**
     * @param accountantMessage the accountant-facing explanation (also logged)
     * @param userMessage       the operator-facing sentence
     */
    public AccountingSetupException(String accountantMessage, String userMessage) {
        super(accountantMessage);
        this.userMessage = userMessage != null ? userMessage : DEFAULT_USER_MESSAGE;
    }

    /** The plain-language sentence for an operator who cannot fix the accounts themselves. */
    public String userMessage() {
        return userMessage;
    }
}
