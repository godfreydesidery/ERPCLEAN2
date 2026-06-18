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
}
