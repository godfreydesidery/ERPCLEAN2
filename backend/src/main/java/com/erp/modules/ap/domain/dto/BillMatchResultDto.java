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
    /**
     * One line's match outcome.
     *
     * <p>Every variance field is NULL when that comparison did not run — a reviewer reading a 0
     * must be able to trust it means "checked and equal", never "nothing was checked".
     * {@code comparisonPerformed} says outright whether the line went through the 3-way control;
     * {@code matchNote} carries the plain-English reason and next step when it did not.
     */
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
            Instant matchedAt,
            boolean comparisonPerformed,
            String matchNote
    ) {}
}
