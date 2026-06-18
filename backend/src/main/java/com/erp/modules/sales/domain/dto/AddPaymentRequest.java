package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.TenderType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Request DTO to add a tender payment to a draft invoice (ADR-0008 D-8, FR-SALES-17).
 * currency must match the invoice header currency (BR-CUR-07).
 *
 * <p>ADR-0041 D3 — optional structured instrument links / refs (all nullable). The service
 * stamps these onto sales_invoice_payments where applicable to the tender type:
 * <ul>
 *   <li>{@code cashBankAccountId} — soft-FK to cash_bank_accounts.id the tender landed in</li>
 *   <li>{@code chequeId} — soft-FK to cheques.id (CHEQUE tender)</li>
 *   <li>{@code mobileMoneyRef} — structured mobile-money transaction reference (MOBILE_MONEY tender)</li>
 *   <li>{@code cardRef} — structured card authorisation reference (CARD tender)</li>
 * </ul>
 */
public record AddPaymentRequest(
        @NotNull TenderType tenderType,
        @NotNull @Positive BigDecimal amount,
        @NotNull String currency,
        String reference,
        // ADR-0041 D3 — optional structured instrument links / refs
        Long cashBankAccountId,
        Long chequeId,
        String mobileMoneyRef,
        String cardRef
) {
    /** Back-compat: tender with no structured instrument links (CASH/MOBILE_MONEY by reference). */
    public AddPaymentRequest(TenderType tenderType, BigDecimal amount, String currency,
                             String reference) {
        this(tenderType, amount, currency, reference, null, null, null, null);
    }
}
