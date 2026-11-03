package com.erp.modules.sales.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.math.BigDecimal;
import java.util.List;

/**
 * Bills paid, summarised per waiter, with the amount split by payment type. {@code tenders} in
 * each row lines up with {@code tenderTypes}. A voided receipt is left out.
 */
public record RestaurantBillsSummaryDto(
        ReportCompanyHeaderDto company,
        String fromDate,
        String toDate,
        String waiterName,
        String currency,
        List<String> tenderTypes,
        List<Row> rows,
        Row totals,
        String generatedAt) {

    public record Row(String waiterUid, String waiterName, long bills, long guests,
                      List<BigDecimal> tenders, BigDecimal amount) {}
}
