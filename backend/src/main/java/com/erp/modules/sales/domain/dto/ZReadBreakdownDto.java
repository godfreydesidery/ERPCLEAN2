package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The sales-report sections of a Z-read: what the shift sold, how, and by whom. Additive to
 * {@link ZReadDto} — older tills and apps ignore it.
 *
 * <p>Same basis as {@link ZReadDto#totalSalesAmount()}: the session's FINALISED invoices less the
 * POS item returns made in the session (ADR-0072), in the invoices' own currency. So
 * {@code netSalesAmount} equals {@code totalSalesAmount}, and every list below sums back to it.
 *
 * @param grossSalesAmount  what the lines would have cost at their prices, before any discount
 * @param discountAmount    everything taken off: line amount/percent discounts and each line's share
 *                          of a whole-bill discount
 * @param netSalesAmount    what was charged (VAT-inclusive) = gross − discount, net of returns
 * @param vatAmount         the VAT inside {@code netSalesAmount}
 * @param transactionCount  finalised sales in the session (the same count as {@code invoiceCount})
 * @param categories        net sales per product category, largest first ("Uncategorised" when none)
 * @param departments       net sales per kitchen/bar station the item was sent to, largest first
 *                          ("Unassigned" for items never sent to a station, e.g. counter sales)
 * @param teamMembers       net sales per person who served the sale (the waiter on a restaurant
 *                          bill, else the cashier), largest first, with their number of sales
 */
public record ZReadBreakdownDto(
        BigDecimal grossSalesAmount,
        BigDecimal discountAmount,
        BigDecimal netSalesAmount,
        BigDecimal vatAmount,
        long transactionCount,
        List<Line> categories,
        List<Line> departments,
        List<Line> teamMembers) {

    /** One named total. {@code count} is the number of sales it covers (0 where not meaningful). */
    public record Line(String name, BigDecimal amount, long count) {}
}
