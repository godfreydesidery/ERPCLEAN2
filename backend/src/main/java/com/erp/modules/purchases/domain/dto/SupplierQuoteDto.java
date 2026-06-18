package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.SupplierQuote;
import com.erp.modules.purchases.domain.enums.SupplierQuoteStatus;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record SupplierQuoteDto(
        Long   id,
        String uid,
        Long   companyId,
        Long   branchId,
        String quoteNumber,
        Long   rfqId,
        String rfqUid,
        Long   supplierId,
        String supplierCode,
        String supplierName,
        SupplierQuoteStatus status,
        LocalDate validUntil,
        Short  leadTimeDays,
        BigDecimal quoteTotalAmount,
        String currency,
        String incoterms,
        String terms,
        BigDecimal score,
        Integer rank,
        String warranty,
        String quoteRef,
        String notes,
        Instant createdAt,
        List<SupplierQuoteLineDto> lines
) {
    public static SupplierQuoteDto from(SupplierQuote q, List<SupplierQuoteLineDto> lines) {
        return new SupplierQuoteDto(
                q.getId(), q.getUid(),
                q.getCompanyId(), q.getBranchId(),
                q.getQuoteNumber(),
                q.getRfqId(), q.getRfqUid(),
                q.getSupplierId(), q.getSupplierCode(), q.getSupplierName(),
                q.getStatus(), q.getValidUntil(), q.getLeadTimeDays(),
                q.getQuoteTotalAmount(), CurrencyCode.value(q.getCurrency()),
                q.getIncoterms(), q.getTerms(), q.getScore(), q.getRank(),
                q.getWarranty(), q.getQuoteRef(), q.getNotes(),
                q.getCreatedAt(), lines);
    }
}
