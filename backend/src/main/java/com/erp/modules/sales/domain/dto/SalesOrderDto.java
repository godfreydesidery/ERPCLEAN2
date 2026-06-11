package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.SalesOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record SalesOrderDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String orderNumber,
        String status,
        Long customerId,
        Long agentId,
        String currency,
        LocalDate orderDate,
        String sourceQuotationUid,
        BigDecimal docDiscountAmount,
        BigDecimal docDiscountPercent,
        BigDecimal netTotalAmount,
        BigDecimal vatTotalAmount,
        BigDecimal grossTotalAmount,
        Instant confirmedAt,
        Instant cancelledAt,
        String cancelReason,
        String notes,
        List<SalesOrderLineDto> lines
) {
    public static SalesOrderDto from(SalesOrder o, List<SalesOrderLineDto> lines) {
        return new SalesOrderDto(
                o.getId(), o.getUid(),
                o.getCompanyId(), o.getBranchId(),
                o.getOrderNumber(),
                o.getStatus().name(),
                o.getCustomerId(), o.getAgentId(),
                o.getCurrency(),
                o.getOrderDate(),
                o.getSourceQuotationUid(),
                o.getDocDiscountAmount(), o.getDocDiscountPercent(),
                o.getNetTotalAmount(), o.getVatTotalAmount(), o.getGrossTotalAmount(),
                o.getConfirmedAt(), o.getCancelledAt(), o.getCancelReason(),
                o.getNotes(),
                lines);
    }
}
