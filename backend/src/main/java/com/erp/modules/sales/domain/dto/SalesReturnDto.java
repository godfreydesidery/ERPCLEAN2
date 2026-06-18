package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.SalesReturn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Response DTO for a sales return / RMA header (ADR-0021 D-11, Stage 2). */
public record SalesReturnDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String returnNumber,
        String status,
        Long deliveryId,
        String deliveryUid,
        String salesOrderUid,
        Long customerId,
        LocalDate returnDate,
        String creditNoteUid,
        String cogsReversalGlEntryUid,
        String reason,
        BigDecimal netAmount,
        BigDecimal vatAmount,
        BigDecimal grossAmount,
        String currency,
        List<SalesReturnLineDto> lines
) {
    public static SalesReturnDto from(SalesReturn r, List<SalesReturnLineDto> lines) {
        return new SalesReturnDto(
                r.getId(), r.getUid(),
                r.getCompanyId(), r.getBranchId(),
                r.getReturnNumber(),
                r.getStatus().name(),
                r.getDeliveryId(), r.getDeliveryUid(), r.getSalesOrderUid(),
                r.getCustomerId(), r.getReturnDate(),
                r.getCreditNoteUid(), r.getCogsReversalGlEntryUid(),
                r.getReason(),
                r.getNetAmount(), r.getVatAmount(), r.getGrossAmount(),
                r.getCurrency().value(),
                lines);
    }
}
