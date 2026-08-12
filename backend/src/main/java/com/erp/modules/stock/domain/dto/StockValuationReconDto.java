package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Reconciliation bar for the stock valuation report (ADR-0020 D-6, BR-INV-06).
 *
 * <p>The bar answers one question: does Σ{@code on_hand_value} agree with the GL Inventory account
 * balance? It has three possible answers, and {@link #status} is the authority on which one applies:
 *
 * <ul>
 *   <li>{@link Status#TIED} — the two agree at the scale money is posted at.</li>
 *   <li>{@link Status#OUT_OF_BALANCE} — they differ by a postable amount. A finance-grade defect:
 *       the stock ledger and the GL are genuinely out of sync (BR-INV-06).</li>
 *   <li>{@link Status#GL_ACCOUNT_NOT_CONFIGURED} — the check could not be run at all because the
 *       Inventory posting account is not mapped (or is not usable). <b>Not</b> a discrepancy: an
 *       unrun check must never be reported as a zero GL balance, because that reads as "the GL is
 *       out by the entire value of your stock".</li>
 * </ul>
 *
 * <h2>Why the verdict is decided at the posting scale</h2>
 *
 * <p>A live UAT printed "Stock ledger total 1,186,500.00 / GL balance 1,186,500.00 /
 * Difference 0.00" and directly beneath it "Reconciled to GL: NO". The raw figures were
 * {@code computed 1186500.0045} against {@code expected 1186500.0000}: a 0.0045 residue that the
 * page rounds away but that flipped the verdict. A document must never assert reconciled and
 * not-reconciled in the same breath.
 *
 * <p>The residue is an arithmetic artifact of the moving-average engine, not a GL break:
 * {@code on_hand_value} is re-derived per row as {@code round4(qty × round4(avg_cost))}
 * (InventoryValuationServiceImpl), so a repeating average such as 1/3 leaves a sub-cent remainder
 * in the stock ledger that no journal could ever carry — money is posted, printed and settled at
 * {@value #POSTING_SCALE} dp.
 *
 * <p>So the verdict is decided on {@link #difference}, which is the difference AT that scale — the
 * exact number the page prints. Verdict and figures can no longer disagree.
 *
 * <p>This does not make the check lazier, and deliberately so:
 * <ul>
 *   <li>Any genuine gap — an unposted GRN, a manual journal on the Inventory account, stock created
 *       with no counter-leg — is at least one cent and still fails the check. Residues also
 *       accumulate: once the drift reaches half a cent it rounds up to 0.01 and trips the alarm.</li>
 *   <li>{@link #exactDifference} carries the unrounded difference, so a sub-cent residue is still
 *       visible in the payload and is named in {@link #message} rather than silently dropped.</li>
 * </ul>
 */
public record StockValuationReconDto(
        String     label,
        /** Σ on_hand_value across all products for the company — exact, as carried in the ledger. */
        BigDecimal computed,
        /** GL Inventory account balance. Null when the check could not be run (never a stand-in 0). */
        BigDecimal expected,
        /** computed − expected AT the posting scale — the figure the page prints and the verdict uses. */
        BigDecimal difference,
        /** computed − expected, unrounded: keeps a sub-cent residue visible. Null when not checked. */
        BigDecimal exactDifference,
        /** True only when {@link #status} is {@link Status#TIED}. */
        boolean    ties,
        Status     status,
        /** User-safe sentence naming the outcome and what to do next. Never internal detail. */
        String     message
) {

    /** The scale money is posted, printed and settled at — the scale the verdict is decided at. */
    public static final int POSTING_SCALE = 2;

    private static final RoundingMode RM = RoundingMode.HALF_UP;

    /** Outcome of the reconciliation. Distinguishes "did not tie" from "could not be checked". */
    public enum Status {
        /** Σ on_hand_value agrees with the GL Inventory balance at the posting scale. */
        TIED,
        /** They differ by a postable amount — investigate (BR-INV-06). */
        OUT_OF_BALANCE,
        /** No usable Inventory posting account, so no comparison was made. */
        GL_ACCOUNT_NOT_CONFIGURED
    }

    /**
     * The reconciliation actually ran: compare the stock ledger total with the GL balance.
     *
     * @param expected the GL Inventory balance — a real balance, including a real zero. Callers must
     *                 NOT pass zero to stand in for "unknown"; use {@link #glAccountNotMapped} or
     *                 {@link #glAccountNotUsable} for that.
     */
    public static StockValuationReconDto of(String label, BigDecimal computed, BigDecimal expected) {
        BigDecimal safeComputed = computed != null ? computed : BigDecimal.ZERO;
        BigDecimal safeExpected = expected != null ? expected : BigDecimal.ZERO;

        BigDecimal exact  = safeComputed.subtract(safeExpected);
        BigDecimal posted = exact.setScale(POSTING_SCALE, RM);
        boolean    tied   = posted.signum() == 0;

        return new StockValuationReconDto(
                label, safeComputed, safeExpected, posted, exact, tied,
                tied ? Status.TIED : Status.OUT_OF_BALANCE,
                tied ? tiedMessage(exact) : outOfBalanceMessage(posted));
    }

    /** No GL account is mapped for the Inventory posting role — the check could not run. */
    public static StockValuationReconDto glAccountNotMapped(String label, BigDecimal computed) {
        return notChecked(label, computed,
                "Stock could not be checked against the ledger because no GL account is set up for "
                        + "Inventory yet. Ask your accountant to map the Inventory account under "
                        + "General Ledger settings, then run this report again.");
    }

    /** A mapping exists but the account cannot be used (inactive, or no longer there). */
    public static StockValuationReconDto glAccountNotUsable(String label, BigDecimal computed) {
        return notChecked(label, computed,
                "Stock could not be checked against the ledger because the GL account set up for "
                        + "Inventory is no longer in use. Ask your accountant to reactivate it, or map "
                        + "a different Inventory account under General Ledger settings, then run this "
                        + "report again.");
    }

    private static StockValuationReconDto notChecked(String label, BigDecimal computed,
                                                      String message) {
        return new StockValuationReconDto(
                label,
                computed != null ? computed : BigDecimal.ZERO,
                null,    // no GL balance was read — zero would read as "the GL is empty"
                null,    // and so there is no difference to state
                null,
                false,   // not reconciled: the check did not run. status says why.
                Status.GL_ACCOUNT_NOT_CONFIGURED,
                message);
    }

    private static String tiedMessage(BigDecimal exact) {
        if (exact.signum() == 0) {
            return "The stock ledger agrees with the GL Inventory account.";
        }
        // Named, not hidden: the residue is smaller than any amount that can be posted, so it is
        // reported as information rather than raised as an alarm.
        return "The stock ledger agrees with the GL Inventory account. A rounding remainder of "
                + exact.abs().stripTrailingZeros().toPlainString()
                + " is carried in the stock ledger; it is smaller than the smallest amount that can "
                + "be posted, so no action is needed.";
    }

    private static String outOfBalanceMessage(BigDecimal posted) {
        return "The stock ledger and the GL Inventory account differ by "
                + posted.abs().toPlainString()
                + ". Check for goods received but not yet posted to the ledger, manual journals on "
                + "the Inventory account, and products still waiting for an opening valuation.";
    }
}
