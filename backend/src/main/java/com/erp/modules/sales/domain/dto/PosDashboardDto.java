package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The till's sales dashboard (Tulia 2026-11-02): finalised sales at the caller's active branch over
 * a date range — per day, per payment type and per department with the items sold in each. Money is
 * in the company's base currency. Never carries cost or profit: the till's audience is the shop,
 * not the back office.
 *
 * @param grossTotal   VAT-inclusive sales, after discounts
 * @param invoiceCount finalised sales in the range
 * @param tenders      what the sales were paid with, net of change (complimentary included)
 * @param departments  the product category; products with none fall under "(no department)"
 */
public record PosDashboardDto(
        String fromDate,
        String toDate,
        String branchName,
        String currency,
        BigDecimal grossTotal,
        long invoiceCount,
        List<Day> days,
        List<Tender> tenders,
        List<Department> departments,
        String generatedAt) {

    /** One day in the company's time zone, ISO {@code yyyy-MM-dd}. */
    public record Day(String date, long invoices, BigDecimal gross) {}

    /** One payment type, e.g. CASH or MOBILE_MONEY. */
    public record Tender(String tenderType, long payments, BigDecimal amount) {}

    /** One department and the items sold in it, biggest first. */
    public record Department(String department, BigDecimal quantity, BigDecimal gross,
                             List<Item> items) {}

    /** Quantity is in the product's base unit ({@code unit}). */
    public record Item(String code, String name, String unit, BigDecimal quantity,
                       BigDecimal gross) {}
}
