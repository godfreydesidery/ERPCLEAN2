package com.erp.modules.sales.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.math.BigDecimal;
import java.util.List;

/**
 * The restaurant dashboard over a date range, also exported as the Flash Report. Sales are every
 * finalised receipt in scope (gross, base currency); orders, guests and waiters come from the
 * restaurant orders paid in the range. {@code previous} is the same number of days just before.
 *
 * @param branchName null when every branch the caller may read is covered
 * @param waiters    paid orders per waiter, plus a {@code waiterUid == null} row for sales made
 *                   without a table order, so the rows add up to {@code current.sales}
 */
public record RestaurantDashboardDto(
        ReportCompanyHeaderDto company,
        String fromDate,
        String toDate,
        String branchName,
        String currency,
        Kpis current,
        Kpis previous,
        String previousFromDate,
        String previousToDate,
        Live live,
        List<Day> days,
        List<Hour> hours,
        List<Weekday> weekdays,
        List<Department> departments,
        List<Item> topItems,
        List<WaiterSalesRowDto> waiters,
        List<Tender> tenders,
        List<Comp> complimentary,
        String generatedAt) {

    /**
     * @param discounts      everything taken off the receipts, VAT-inclusive
     * @param complimentary  paid with the COMPLIMENTARY tender
     * @param refunds        cash paid back at the till (POS refund payouts)
     * @param voidedReceipts receipts sold in the range and voided since
     * @param voidedItems    quantity of order items voided in the range
     */
    public record Kpis(BigDecimal sales, long receipts, BigDecimal averageReceipt, long orders,
                       long guests, BigDecimal spendPerGuest, BigDecimal discounts,
                       BigDecimal complimentary, BigDecimal refunds, long voidedReceipts,
                       long cancelledOrders, BigDecimal voidedItems) {}

    /** Right now, whatever the date range: orders open, and bills printed but not paid. */
    public record Live(long openOrders, long billedOrders, BigDecimal billedAmount) {}

    /** One day, {@code yyyy-MM-dd} in the company's time zone. */
    public record Day(String date, long receipts, BigDecimal sales) {}

    /** One hour of the day, 0–23, summed over the range. */
    public record Hour(int hour, long receipts, BigDecimal sales) {}

    /**
     * One day of the week, 1 = Monday. {@code days} is how many of that weekday the range holds,
     * so {@code averagePerDay} compares a range with three Mondays to one with four fairly.
     */
    public record Weekday(int day, String label, int days, long receipts, BigDecimal sales,
                          BigDecimal averagePerDay) {}

    /** A product category; products with none fall under "(no department)". */
    public record Department(String name, BigDecimal quantity, BigDecimal sales) {}

    public record Item(String code, String name, String unit, BigDecimal quantity,
                       BigDecimal sales) {}

    /** A payment type, net of change. */
    public record Tender(String tenderType, long payments, BigDecimal amount) {}

    /** One complimentary settlement and who it was given to. */
    public record Comp(String receiptNumber, String at, String givenTo, BigDecimal amount) {}
}
