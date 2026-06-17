package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.PurchaseReturn;
import com.erp.modules.purchases.domain.enums.PurchaseReturnStatus;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PurchaseReturnDto(
        Long   id,
        String uid,
        Long   companyId,
        Long   branchId,
        String returnNumber,
        PurchaseReturnStatus status,
        Long   goodsReceiptId,
        String goodsReceiptUid,
        Long   supplierId,
        String supplierCode,
        String supplierName,
        String reason,
        BigDecimal netAmount,
        BigDecimal vatAmount,
        BigDecimal grossAmount,
        String currency,
        String debitNoteUid,
        String glEntryUid,
        Instant confirmedAt,
        Instant createdAt,
        List<PurchaseReturnLineDto> lines
) {
    public static PurchaseReturnDto from(PurchaseReturn r, List<PurchaseReturnLineDto> lines) {
        return new PurchaseReturnDto(
                r.getId(), r.getUid(),
                r.getCompanyId(), r.getBranchId(),
                r.getReturnNumber(), r.getStatus(),
                r.getGoodsReceiptId(), r.getGoodsReceiptUid(),
                r.getSupplierId(), r.getSupplierCode(), r.getSupplierName(),
                r.getReason(),
                r.getNetAmount(), r.getVatAmount(), r.getGrossAmount(),
                CurrencyCode.value(r.getCurrency()), r.getDebitNoteUid(), r.getGlEntryUid(),
                r.getConfirmedAt(), r.getCreatedAt(),
                lines);
    }
}
