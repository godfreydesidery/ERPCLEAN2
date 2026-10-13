package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * One counter tender on a finalised invoice, as the GL sale posting needs it (SAL-06 / ACC-04 /
 * ACC-05). Sales-owned DTO; GL imports this, never the payment entity.
 *
 * @param tenderType        the {@code TenderType} name (CASH, MOBILE_MONEY, CHEQUE, CARD)
 * @param cashBankAccountId the cash/bank account the tender landed in
 *                          ({@code sales_invoice_payments.cash_bank_account_id}); null when the
 *                          tender named none — the GL then uses the company's CASH mapping
 * @param netAmount         amount − change, in the invoice currency (may be zero or negative when a
 *                          change row exceeds its own tender — the posting keeps it as recorded)
 */
public record InvoicePostingTenderDto(
        String tenderType,
        Long cashBankAccountId,
        BigDecimal netAmount
) {}
