package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.SalesOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Full response DTO for a SalesOrder header.
 * Carries both {@code id} (JSON string via global Long-as-string config) and {@code uid}.
 * customerName/customerCode/agentName are enrichment fields resolved at read time by the
 * service (mirrors SalesInvoiceDto) so the frontend never has to display a raw numeric FK.
 */
public record SalesOrderDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String orderNumber,
        String status,
        Long customerId,
        String customerName,
        String customerCode,
        Long agentId,
        String agentName,
        String currency,
        LocalDate orderDate,
        String customerPoNumber,
        LocalDate requestedDeliveryDate,
        LocalDate promisedDate,
        Long paymentTermsId,
        String sourceQuotationUid,
        String sourceOpportunityUid,
        BigDecimal docDiscountAmount,
        BigDecimal docDiscountPercent,
        BigDecimal netTotalAmount,
        BigDecimal vatTotalAmount,
        BigDecimal grossTotalAmount,
        Instant confirmedAt,
        Instant cancelledAt,
        String cancelReason,
        String notes,
        Long shipToAddressId,
        Long billToAddressId,
        String shipToAddressText,
        String billToAddressText,
        List<SalesOrderLineDto> lines
) {
    /** Build from entity with enriched customer and agent fields (mirrors SalesInvoiceDto.from). */
    public static SalesOrderDto from(SalesOrder o, List<SalesOrderLineDto> lines,
                                     String customerName, String customerCode, String agentName) {
        return new SalesOrderDto(
                o.getId(), o.getUid(),
                o.getCompanyId(), o.getBranchId(),
                o.getOrderNumber(),
                o.getStatus().name(),
                o.getCustomerId(), customerName, customerCode,
                o.getAgentId(), agentName,
                o.getCurrency().value(),
                o.getOrderDate(),
                o.getCustomerPoNumber(), o.getRequestedDeliveryDate(), o.getPromisedDate(),
                o.getPaymentTermsId(),
                o.getSourceQuotationUid(),
                o.getSourceOpportunityUid(),
                o.getDocDiscountAmount(), o.getDocDiscountPercent(),
                o.getNetTotalAmount(), o.getVatTotalAmount(), o.getGrossTotalAmount(),
                o.getConfirmedAt(), o.getCancelledAt(), o.getCancelReason(),
                o.getNotes(),
                o.getShipToAddressId(), o.getBillToAddressId(),
                o.getShipToAddressText(), o.getBillToAddressText(),
                lines);
    }
}
