package com.erp.modules.sales.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.math.BigDecimal;
import java.util.List;

/**
 * Sales by hour of the day and by day of the week, each split by department. A receipt counts at
 * the time it was paid. {@code departmentSales} lines up with {@code departments}.
 */
public record RestaurantDaypartDto(
        ReportCompanyHeaderDto company,
        String fromDate,
        String toDate,
        String branchName,
        String currency,
        List<String> departments,
        List<Slot> hours,
        List<Slot> weekdays,
        Slot totals,
        String generatedAt) {

    /**
     * @param label "07:00-08:00", or "Monday"
     * @param days  how many days of the range this slot covers (every day, for an hour)
     */
    public record Slot(String label, long receipts, BigDecimal sales, int days,
                       BigDecimal averagePerDay, List<BigDecimal> departmentSales) {}
}
