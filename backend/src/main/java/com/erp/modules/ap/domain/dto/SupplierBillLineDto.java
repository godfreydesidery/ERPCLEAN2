package com.erp.modules.ap.domain.dto;

import com.erp.modules.products.domain.enums.VatStatus;
import java.math.BigDecimal;

/**
 * Response DTO for a supplier bill line.
 * D-8: exposes per-line VAT fields (vatStatus, vatRate, lineVatAmount) and optional GL account
 * override (glAccountId) used by the bill-match poster for service-line debit routing.
 */
public record SupplierBillLineDto(
        Long id,
        String uid,
        Long supplierBillId,
        short lineNo,
        Long productId,
        String poLineUid,
        String grLineUid,
        String description,
        BigDecimal billedQty,
        BigDecimal unitCostAmount,
        BigDecimal lineNetAmount,
        String currency,
        // D-8: per-line VAT
        VatStatus vatStatus,
        BigDecimal vatRate,
        BigDecimal lineVatAmount,
        // D-8: optional GL account override for service lines
        Long glAccountId,
        // P2: per-line dimension tags (soft refs → dimension_values.id)
        Long costCentreValueId,
        Long departmentValueId
) {}
