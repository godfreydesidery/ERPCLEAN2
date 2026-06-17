package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.LandedCost;
import com.erp.modules.purchases.domain.enums.LandedCostBasis;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.modules.purchases.domain.enums.LandedCostStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record LandedCostDto(
        Long   id,
        String uid,
        Long   companyId,
        Long   branchId,
        String landedCostNumber,
        LandedCostStatus status,
        LandedCostBasis basis,
        BigDecimal totalChargeAmount,
        String currency,
        String glEntryUid,
        String notes,
        Instant confirmedAt,
        Instant createdAt,
        List<String> receiptUids,
        List<LandedCostChargeDto> charges
) {
    public static LandedCostDto from(LandedCost lc, List<String> receiptUids,
                                     List<LandedCostChargeDto> charges) {
        return new LandedCostDto(
                lc.getId(), lc.getUid(),
                lc.getCompanyId(), lc.getBranchId(),
                lc.getLandedCostNumber(), lc.getStatus(), lc.getBasis(),
                lc.getTotalChargeAmount(), CurrencyCode.value(lc.getCurrency()),
                lc.getGlEntryUid(), lc.getNotes(),
                lc.getConfirmedAt(), lc.getCreatedAt(),
                receiptUids, charges);
    }
}
