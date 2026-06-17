package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.PurchaseOrder;
import com.erp.modules.purchases.domain.enums.PurchaseOrderStatus;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only response DTO for a Purchase Order (ADR-0011 D-12).
 *
 * <p>Enriched with {@code supplierName} (snapshot on the entity) and the PO lines.
 * id and uid both present (PROJECT-CONVENTIONS §3.3).
 */
public record PurchaseOrderDto(
        Long   id,
        String uid,
        Long   companyId,
        Long   branchId,
        String orderNumber,
        PurchaseOrderStatus status,
        Long   supplierId,
        String supplierCode,
        String supplierName,
        String currency,
        BigDecimal orderTotalAmount,
        LocalDate  expectedDate,
        String     notes,
        Instant    orderedAt,
        Instant    voidedAt,
        String     voidReason,
        Instant    closedAt,
        Instant    createdAt,
        List<PurchaseOrderLineDto> lines
) {
    public static PurchaseOrderDto from(PurchaseOrder po, List<PurchaseOrderLineDto> lines) {
        return new PurchaseOrderDto(
                po.getId(), po.getUid(),
                po.getCompanyId(), po.getBranchId(),
                po.getOrderNumber(), po.getStatus(),
                po.getSupplierId(), po.getSupplierCode(), po.getSupplierName(),
                CurrencyCode.value(po.getCurrency()), po.getOrderTotalAmount(),
                po.getExpectedDate(), po.getNotes(),
                po.getOrderedAt(), po.getVoidedAt(), po.getVoidReason(),
                po.getClosedAt(), po.getCreatedAt(),
                lines);
    }
}
