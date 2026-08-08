package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.TenderType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Quick-sale request via a POS session (ADR-0029 D-5).
 * Creates a DIRECT SalesInvoice tagged with the session uid and immediately finalises it.
 */
public record PosSaleRequest(
        @NotBlank String sessionUid,
        @NotNull  Long customerId,
        /**
         * Selling agent for the sale (optional, ADR-0042 D-4). <strong>The submitted value IS
         * applied</strong>: it is resolved to the agent's uid, scoped to the session's company, and
         * carried onto the created invoice — so it drives agent commission and every agent-filtered
         * sales report. An id that does not belong to the session's company is rejected as not found.
         *
         * <p>Omit it (null) to fall back to the signed-in user's own agent record; a cashier with no
         * agent record and no {@code agentId} cannot sell at all (BR-SALES-06). This field was once
         * documented as ignored while the code already honoured it — the mismatch caused a production
         * defect, so keep this note true to {@code PosSaleServiceImpl}.
         */
        Long agentId,
        @NotBlank String currency,
        @NotEmpty @Valid List<LineItem> lines,
        /**
         * Optional tenders (ADR-0042 D-3). When present, each is recorded as an invoice payment
         * (CASH / CARD / MOBILE_MONEY / CHEQUE) and their sum must cover the gross total. When absent
         * or empty, the sale is settled as a single exact CASH payment — the original behaviour.
         */
        @Valid List<PosTender> tenders,
        /** Total tendered (for receipt printing, not stored on invoice). */
        BigDecimal tenderedAmount,
        @Size(max = 500) String notes,
        /**
         * Age-verification acknowledgement (ADR-0044 D-3a, BR-11). Optional — existing clients
         * that omit this field are treated as {@code false}. When any sale line contains a product
         * with {@code restrictedKind != NONE}, either this must be {@code true} OR the cashier
         * must hold {@code POS.SALE.AGE_OVERRIDE}; otherwise the sale is rejected with 409.
         */
        Boolean ageVerified,
        /**
         * Below-cost approval acknowledgement (V93). Optional — existing clients that omit this
         * field are treated as {@code false}. Only consulted when the company's below-cost policy is
         * {@code APPROVE}: a line priced at or below cost then goes through if this is {@code true}
         * AND the cashier holds {@code SALES.BELOW_COST.OVERRIDE}; otherwise the sale is rejected
         * with 409. Passed straight through to the invoice finalise, which is where the policy is
         * enforced.
         */
        Boolean belowCostApproved,
        /**
         * When the till captured this basket (K11 stale-replay guard). Optional — clients that omit
         * it behave exactly as before.
         *
         * <p>The "unfinished sale" dialog keeps a basket on the device until it is resolved. If that
         * basket is submitted days later it would be re-priced at today's prices and taken out of
         * today's stock, silently posting an old sale into the current period. When this timestamp is
         * present and older than {@code erp.pos.sale.max-age}, the sale is refused with
         * {@code STALE_REPLAY} instead — the cashier is told to ring it again.
         *
         * <p>A value in the future is ignored (clock skew must never block a live till).
         */
        java.time.Instant capturedAt
) {

    /** Backward-compatible constructor (no tenders, no ageVerified) — existing callers unaffected. */
    public PosSaleRequest(String sessionUid, Long customerId, Long agentId, String currency,
                          List<LineItem> lines, BigDecimal tenderedAmount, String notes) {
        this(sessionUid, customerId, agentId, currency, lines, null, tenderedAmount, notes,
                null, null, null);
    }

    /** Backward-compatible constructor (tenders, no ageVerified) — preserves ADR-0042 D-3 callers. */
    public PosSaleRequest(String sessionUid, Long customerId, Long agentId, String currency,
                          List<LineItem> lines, List<PosTender> tenders,
                          BigDecimal tenderedAmount, String notes) {
        this(sessionUid, customerId, agentId, currency, lines, tenders, tenderedAmount, notes,
                null, null, null);
    }

    /** Backward-compatible constructor (ageVerified, no below-cost approval) — ADR-0044 D-3a callers. */
    public PosSaleRequest(String sessionUid, Long customerId, Long agentId, String currency,
                          List<LineItem> lines, List<PosTender> tenders,
                          BigDecimal tenderedAmount, String notes, Boolean ageVerified) {
        this(sessionUid, customerId, agentId, currency, lines, tenders, tenderedAmount, notes,
                ageVerified, null, null);
    }

    /** Backward-compatible constructor (below-cost approval, no capturedAt) — V93 callers. */
    public PosSaleRequest(String sessionUid, Long customerId, Long agentId, String currency,
                          List<LineItem> lines, List<PosTender> tenders,
                          BigDecimal tenderedAmount, String notes, Boolean ageVerified,
                          Boolean belowCostApproved) {
        this(sessionUid, customerId, agentId, currency, lines, tenders, tenderedAmount, notes,
                ageVerified, belowCostApproved, null);
    }

    public record LineItem(
            @NotNull Long productId,
            @NotNull Long unitId,
            @NotNull @DecimalMin("0.0001") BigDecimal quantity,
            /**
             * Optional (ADR-0042 D-4). IGNORED — pricing is server-authoritative (resolved from the
             * product's price list); the submitted value has no effect. Express negotiated reductions
             * via {@code lineDiscountAmount}.
             */
            BigDecimal unitPrice,
            BigDecimal lineDiscountAmount,
            /**
             * Optional (K7). The {@code authoriserUid} from a successful manager step-up
             * ({@code POST /api/v1/auth/verify-authority}, permission {@code SALES.DISCOUNT.OVERRIDE}).
             * Per LINE, not per sale: a basket may contain one heavily discounted item and nine
             * ordinary ones, and a single sale-level flag would let the approval for the first wave
             * the rest through. Only consulted when the company's discount policy is APPROVE and the
             * line's discount exceeds the ceiling; the server re-verifies the named user's authority.
             * Existing clients omit it and are unaffected — the policy ships OFF.
             */
            @Size(max = 26) String discountAuthorisedByUid
    ) {

        /** Back-compat: a till line with no manager authorisation attached. */
        public LineItem(Long productId, Long unitId, BigDecimal quantity,
                        BigDecimal unitPrice, BigDecimal lineDiscountAmount) {
            this(productId, unitId, quantity, unitPrice, lineDiscountAmount, null);
        }
    }

    /**
     * A single POS tender (ADR-0042 D-3). The optional instrument fields mirror
     * {@code AddPaymentRequest} (ADR-0041 D3) — populate the one relevant to the tender type.
     */
    public record PosTender(
            @NotNull TenderType tenderType,
            @NotNull @DecimalMin("0.0001") BigDecimal amount,
            String reference,
            Long cashBankAccountId,
            Long chequeId,
            String mobileMoneyRef,
            String cardRef
    ) {}
}
