package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.LandedCostCharge;
import com.erp.modules.purchases.domain.enums.LandedCostChargeType;
import java.math.BigDecimal;

public record LandedCostChargeDto(
        Long   id,
        String uid,
        int    lineNo,
        LandedCostChargeType chargeType,
        BigDecimal amount,
        boolean billed,
        String supplierBillUid,
        String currency
) {
    public static LandedCostChargeDto from(LandedCostCharge c) {
        return new LandedCostChargeDto(
                c.getId(), c.getUid(), c.getLineNo(),
                c.getChargeType(), c.getAmount(),
                c.isBilled(), c.getSupplierBillUid(), c.getCurrency());
    }
}
