package com.erp.modules.ap.domain.dto;

import com.erp.modules.ap.domain.enums.SupplierBillSource;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SupplierBillDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        Long supplierId,
        String billNumber,
        String supplierInvoiceNo,
        SupplierBillSource source,
        String purchaseOrderUid,
        LocalDate billDate,
        LocalDate dueDate,
        BigDecimal netAmount,
        BigDecimal vatAmount,
        BigDecimal grossAmount,
        BigDecimal outstandingAmount,
        String currency,
        SupplierBillStatus status,
        String postedGlEntryUid,
        List<SupplierBillLineDto> lines
) {}
