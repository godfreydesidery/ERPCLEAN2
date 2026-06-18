package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.SalesInvoicePayment;
import com.erp.modules.sales.domain.enums.TenderType;
import java.math.BigDecimal;

/**
 * Response DTO for a SalesInvoicePayment (ADR-0008 D-12).
 */
public record SalesInvoicePaymentDto(
        Long id,
        String uid,
        Long invoiceId,
        Long companyId,
        Long branchId,
        TenderType tenderType,
        BigDecimal amount,
        String currency,
        BigDecimal changeAmount,
        String reference,
        // ADR-0041 D3 — structured instrument links / refs (nullable)
        Long cashBankAccountId,
        Long chequeId,
        String mobileMoneyRef,
        String cardRef,
        String receivedAt,
        Long receivedBy,
        String createdAt,
        Long createdBy
) {

    public static SalesInvoicePaymentDto from(SalesInvoicePayment p) {
        return new SalesInvoicePaymentDto(
                p.getId(),
                p.getUid(),
                p.getInvoice().getId(),
                p.getCompanyId(),
                p.getBranchId(),
                p.getTenderType(),
                p.getAmount(),
                p.getCurrency().value(),
                p.getChangeAmount(),
                p.getReference(),
                p.getCashBankAccountId(),
                p.getChequeId(),
                p.getMobileMoneyRef(),
                p.getCardRef(),
                p.getReceivedAt() != null ? p.getReceivedAt().toString() : null,
                p.getReceivedBy(),
                p.getCreatedAt() != null ? p.getCreatedAt().toString() : null,
                p.getCreatedBy()
        );
    }
}
