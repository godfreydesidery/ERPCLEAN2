package com.erp.modules.ap.domain.dto;

import java.math.BigDecimal;

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
        String currency
) {}
