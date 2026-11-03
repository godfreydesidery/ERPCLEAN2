package com.erp.modules.sales.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.math.BigDecimal;
import java.util.List;

/**
 * Sales by waiter in detail: the items on each waiter's paid bills. Rows come waiter by waiter
 * (biggest seller first), items biggest first; {@code waiters} carries each waiter's subtotal.
 */
public record WaiterItemsReportDto(
        ReportCompanyHeaderDto company,
        String fromDate,
        String toDate,
        String waiterName,
        String currency,
        List<Row> rows,
        List<Subtotal> waiters,
        BigDecimal totalAmount,
        String generatedAt) {

    /** Quantity is in the unit sold. */
    public record Row(String waiterUid, String waiterName, String code, String name, String unit,
                      BigDecimal quantity, BigDecimal amount) {}

    public record Subtotal(String waiterUid, String waiterName, long bills, BigDecimal amount) {}
}
