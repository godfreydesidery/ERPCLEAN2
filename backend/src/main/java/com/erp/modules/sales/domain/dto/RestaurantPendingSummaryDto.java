package com.erp.modules.sales.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.math.BigDecimal;
import java.util.List;

/** Pending bills right now, summarised per waiter: open orders and bills printed but unpaid. */
public record RestaurantPendingSummaryDto(
        ReportCompanyHeaderDto company,
        String waiterName,
        String currency,
        List<Row> rows,
        Row totals,
        String generatedAt) {

    /**
     * @param billedAmount   what the printed bills say; an open order has no amount yet
     * @param oldestOpenedAt the longest-waiting order, {@code yyyy-MM-dd HH:mm}
     */
    public record Row(String waiterUid, String waiterName, long open, long billed,
                      BigDecimal items, BigDecimal billedAmount, String oldestOpenedAt) {}
}
