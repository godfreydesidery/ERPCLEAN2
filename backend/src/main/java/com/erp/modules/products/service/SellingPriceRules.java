package com.erp.modules.products.service;

import com.erp.modules.products.domain.entity.PriceList;
import com.erp.modules.products.domain.entity.ProductPrice;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.money.CurrencyCode;
import java.time.LocalDate;
import java.util.Comparator;

/**
 * The few row-level facts the selling-price resolver judges a {@code product_prices} row by
 * (PRD-01 / SAL-04), kept in one place so the resolver and the price listing the deployed till reads
 * ({@code ProductServiceImpl.listPrices}) can never disagree about which rows count.
 *
 * <p>A row can price a sale on a given day only when:
 * <ul>
 *   <li>its price list is ACTIVE (an ARCHIVED list prices nothing — archiving it is how an owner
 *       retires last year's prices);</li>
 *   <li>that day falls inside the list's {@code effective_from}/{@code effective_to} window, and
 *       inside the row's own window (both inclusive; null = open-ended);</li>
 *   <li>it actually carries an amount.</li>
 * </ul>
 */
final class SellingPriceRules {

    private SellingPriceRules() {
        // Static rules.
    }

    /** Whether {@code list} may price a sale on {@code date}. */
    static boolean listUsable(PriceList list, LocalDate date) {
        return list != null
                && list.getStatus() == MasterStatus.ACTIVE
                && within(list.getEffectiveFrom(), list.getEffectiveTo(), date);
    }

    /** Whether {@code row} may price a sale on {@code date} — its list, its own window, an amount. */
    static boolean rowUsable(ProductPrice row, LocalDate date) {
        return listUsable(row.getPriceList(), date)
                && within(row.getEffectiveFrom(), row.getEffectiveTo(), date)
                && row.getPrice() != null
                && row.getPrice().getAmount() != null;
    }

    /** Whether the row is stored in {@code currency}; a null currency matches every row. */
    static boolean currencyMatches(ProductPrice row, String currency) {
        if (currency == null) {
            return true;
        }
        String rowCurrency = row.getPrice() == null ? null : CurrencyCode.value(row.getPrice().getCurrency());
        return currency.equalsIgnoreCase(rowCurrency);
    }

    /**
     * The order the walk-in resolver prefers rows in: usable rows on the company's default list
     * first (lowest list id when several lists carry the flag), then the other usable rows oldest
     * first (the legacy lowest-id tie-break), then the rows that cannot price anything today.
     *
     * <p>Exposed so the plain price listing can be returned in this order: the deployed OrbixPOS
     * till previews the FIRST row of that listing in the sale currency, so ordering it the way the
     * server resolves keeps the price the cashier sees equal to the price the sale posts, without a
     * till release.
     */
    static Comparator<ProductPrice> walkInPreference(LocalDate date) {
        return Comparator
                .comparingInt((ProductPrice row) -> rowUsable(row, date) ? 0 : 1)
                .thenComparingInt(row -> onUsableDefault(row, date) ? 0 : 1)
                .thenComparingLong(row -> onUsableDefault(row, date) ? idOrMax(row.getPriceList().getId()) : 0L)
                .thenComparingLong(row -> idOrMax(row.getId()));
    }

    private static boolean onUsableDefault(ProductPrice row, LocalDate date) {
        return rowUsable(row, date) && row.getPriceList().isDefault();
    }

    private static long idOrMax(Long id) {
        return id == null ? Long.MAX_VALUE : id;
    }

    private static boolean within(LocalDate from, LocalDate to, LocalDate date) {
        return (from == null || !from.isAfter(date)) && (to == null || !to.isBefore(date));
    }
}
