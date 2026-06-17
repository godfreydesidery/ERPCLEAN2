package com.erp.modules.tax.domain.dto;

import com.erp.modules.tax.domain.enums.VatReturnStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * VAT return response DTO (ADR-0017 D-1).
 * P2-M1: added payment tracking fields (paidAt, paidAmount, paymentReference)
 * and turnover figures (salesTurnover, purchasesTurnover, zeroRatedSales, exemptSales).
 */
public record VatReturnDto(
        Long id,
        String uid,
        Long companyId,
        String returnNumber,
        short periodYear,
        short periodMonth,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate dueDate,
        VatReturnStatus status,
        BigDecimal outputVat,
        BigDecimal inputVat,
        BigDecimal adjustmentsTotal,
        BigDecimal openingCredit,
        BigDecimal netVat,
        BigDecimal closingCredit,
        Long priorReturnId,
        String filingReference,
        LocalDate filingDate,
        String postedJournalUid,
        Instant filedAt,
        Long filedBy,
        // P2-M1: payment tracking
        Instant paidAt,
        BigDecimal paidAmount,
        String paymentReference,
        // P2-M1: turnover figures
        BigDecimal salesTurnover,
        BigDecimal purchasesTurnover,
        BigDecimal zeroRatedSales,
        BigDecimal exemptSales,
        List<VatReturnBandDto> bands
) {}
