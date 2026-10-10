package com.erp.modules.gl.domain.enums;

/**
 * Classifies a GL account as a system-controlled sub-ledger control account (D-1, ADR-0040).
 *
 * <p>NULL (absent) on {@link com.erp.modules.gl.domain.entity.ChartOfAccount} means an ordinary
 * account that has no special control-account semantics. A non-null value means the account is
 * owned by a specific sub-ledger and must not be targeted by manual journals.
 *
 * <ul>
 *   <li>{@code AR}               — Accounts Receivable control (sub-ledger: AR/Sales)</li>
 *   <li>{@code AP}               — Accounts Payable control (sub-ledger: AP/Procurement)</li>
 *   <li>{@code BANK}             — Bank clearing control</li>
 *   <li>{@code CASH}             — Cash/till control</li>
 *   <li>{@code INVENTORY}        — Inventory asset control (sub-ledger: stock valuation)</li>
 *   <li>{@code TAX}              — VAT / WHT payable/receivable control</li>
 *   <li>{@code PAYROLL_CLEARING} — Payroll net-wages clearing control</li>
 *   <li>{@code FX_CLEARING}      — Realized / unrealized FX gain-loss clearing</li>
 * </ul>
 *
 * <p>Classification (a non-null {@code control_type}) is distinct from the manual-posting block:
 * see {@link #blocksManualPosting()}. Cash/bank are classified controls yet remain manually postable.
 */
public enum ControlType {
    AR,
    AP,
    BANK,
    CASH,
    INVENTORY,
    TAX,
    PAYROLL_CLEARING,
    FX_CLEARING;

    /**
     * Whether accounts of this control type must reject <em>manual</em> journal entries (D-1, ADR-0040).
     *
     * <p>The genuine sub-ledger / reconciliation controls ({@code AR}, {@code AP}, {@code INVENTORY},
     * {@code TAX}, {@code PAYROLL_CLEARING}, {@code FX_CLEARING}) are owned by a posting engine, and a
     * direct manual journal would silently break the sub-ledger tie — so they are stamped
     * {@code allow_manual_posting = false}.
     *
     * <p>{@code CASH} and {@code BANK} are classified as control accounts (useful for reporting and
     * grouping) but are reconciled through the cash/bank module and bank reconciliation, which
     * <em>expect</em> manual journals (bank charges, interest, corrections). Blocking them is stricter
     * than the mainstream (SAP reconciliation accounts, NetSuite and QuickBooks all permit bank/cash
     * journals), so they keep {@code allow_manual_posting = true}.
     */
    public boolean blocksManualPosting() {
        return this != CASH && this != BANK;
    }

    /**
     * Where an accountant posts to an account of this control type instead of a manual journal
     * (ACC-18) — the sentence the manual-journal refusal and the account picker show.
     */
    public String manualPostingGuidance() {
        return switch (this) {
            case AR -> "Customer balances are posted from Receivables: record a receipt, credit note"
                    + " or write-off there.";
            case AP -> "Supplier balances are posted from Payables: enter a supplier bill, payment or"
                    + " debit note there.";
            case INVENTORY -> "Stock accounts are posted by the Stock module: use a goods receipt,"
                    + " stock adjustment or opening stock instead.";
            case TAX -> "VAT and tax accounts are posted by sales, purchases and the VAT Return"
                    + " screen, not by manual journals.";
            case PAYROLL_CLEARING -> "Payroll liability accounts are posted by payroll runs.";
            case FX_CLEARING -> "Exchange gain and loss accounts are posted by FX revaluation and"
                    + " settlement.";
            case CASH, BANK -> "Cash and bank accounts can take manual journals.";
        };
    }
}
