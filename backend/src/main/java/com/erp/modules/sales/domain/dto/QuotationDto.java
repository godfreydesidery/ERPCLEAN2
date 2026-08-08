package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.Quotation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A sales quotation as returned by the API and consumed by the document renderer.
 *
 * <p>{@code customerName} is denormalised onto the DTO because a printed proforma has to show who
 * it is addressed to, and the renderer reads the source DTO only — it never reaches into the
 * parties module. Nullable: a quotation whose customer row has since been removed still prints,
 * with the customer line simply omitted, rather than failing at print time.
 */
public record QuotationDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String quoteNumber,
        String status,
        Long customerId,
        String customerName,
        Long agentId,
        String currency,
        LocalDate quoteDate,
        LocalDate validUntil,
        String customerPoNumber,
        Integer revisionNo,
        BigDecimal probability,
        Long paymentTermsId,
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
        String sourceOpportunityUid,
        List<QuotationLineDto> lines
) {
    /**
     * @param customerName the customer's display name, or {@code null} when it cannot be resolved
     */
    public static QuotationDto from(Quotation q, String customerName, List<QuotationLineDto> lines) {
        return new QuotationDto(
                q.getId(), q.getUid(),
                q.getCompanyId(), q.getBranchId(),
                q.getQuoteNumber(),
                q.getStatus().name(),
                q.getCustomerId(), customerName, q.getAgentId(),
                q.getCurrency().value(),
                q.getQuoteDate(), q.getValidUntil(),
                q.getCustomerPoNumber(), q.getRevisionNo(), q.getProbability(),
                q.getPaymentTermsId(),
                q.getDocDiscountAmount(), q.getDocDiscountPercent(),
                q.getNetTotalAmount(), q.getVatTotalAmount(), q.getGrossTotalAmount(),
                q.getNotes(),
                q.getSentAt(), q.getAcceptedAt(), q.getRejectedAt(), q.getExpiredAt(),
                q.getConvertedOrderUid(),
                q.getSourceOpportunityUid(),
                lines);
    }
}
