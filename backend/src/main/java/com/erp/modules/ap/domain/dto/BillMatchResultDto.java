package com.erp.modules.ap.domain.dto;

import com.erp.modules.ap.domain.enums.BillMatchStatus;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BillMatchResultDto(
        String billUid,
        SupplierBillStatus billStatus,
        List<LineMatchDto> lineResults
) {
    public record LineMatchDto(
            Long billLineId,
            String billLineUid,
            BillMatchStatus matchStatus,
            BigDecimal priceVarianceAmount,
            BigDecimal priceVariancePct,
            BigDecimal qtyVariance,
            BigDecimal poUnitCostAmount,
            BigDecimal grReceivedQty,
            BigDecimal billedQty,
            Instant matchedAt
    ) {}
}
