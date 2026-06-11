package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.Quotation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record QuotationDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String quoteNumber,
        String status,
        Long customerId,
        Long agentId,
        String currency,
        LocalDate quoteDate,
        LocalDate validUntil,
        BigDecimal docDiscountAmount,
        BigDecimal docDiscountPercent,
        BigDecimal netTotalAmount,
        BigDecimal vatTotalAmount,
        BigDecimal grossTotalAmount,
        String notes,
        Instant sentAt,
        Instant acceptedAt,
        Instant rejectedAt,
        Instant expiredAt,
        String convertedOrderUid,
        List<QuotationLineDto> lines
) {
    public static QuotationDto from(Quotation q, List<QuotationLineDto> lines) {
        return new QuotationDto(
                q.getId(), q.getUid(),
                q.getCompanyId(), q.getBranchId(),
                q.getQuoteNumber(),
                q.getStatus().name(),
                q.getCustomerId(), q.getAgentId(),
                q.getCurrency(),
                q.getQuoteDate(), q.getValidUntil(),
                q.getDocDiscountAmount(), q.getDocDiscountPercent(),
                q.getNetTotalAmount(), q.getVatTotalAmount(), q.getGrossTotalAmount(),
                q.getNotes(),
                q.getSentAt(), q.getAcceptedAt(), q.getRejectedAt(), q.getExpiredAt(),
                q.getConvertedOrderUid(),
                lines);
    }
}
