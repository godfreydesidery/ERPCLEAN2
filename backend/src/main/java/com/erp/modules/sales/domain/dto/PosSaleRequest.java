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
         * Optional (ADR-0042 D-4). Currently informational — a POS sale is recorded against the
         * logged-in cashier; the submitted value is not applied.
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
        Boolean ageVerified
) {

    /** Backward-compatible constructor (no tenders, no ageVerified) — existing callers unaffected. */
    public PosSaleRequest(String sessionUid, Long customerId, Long agentId, String currency,
                          List<LineItem> lines, BigDecimal tenderedAmount, String notes) {
        this(sessionUid, customerId, agentId, currency, lines, null, tenderedAmount, notes, null);
    }

    /** Backward-compatible constructor (tenders, no ageVerified) — preserves ADR-0042 D-3 callers. */
    public PosSaleRequest(String sessionUid, Long customerId, Long agentId, String currency,
                          List<LineItem> lines, List<PosTender> tenders,
                          BigDecimal tenderedAmount, String notes) {
        this(sessionUid, customerId, agentId, currency, lines, tenders, tenderedAmount, notes, null);
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
            BigDecimal lineDiscountAmount
    ) {}

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
