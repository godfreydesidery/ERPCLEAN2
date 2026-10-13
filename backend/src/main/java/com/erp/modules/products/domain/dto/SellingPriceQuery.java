package com.erp.modules.products.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Input to the selling-price resolver: "what does this company charge THIS customer for one of
 * {@code unitId} of {@code productId}?" (PRD-01 / SAL-04 / POS-12 / LSF-02).
 *
 * <p>All ids are database ids the caller has already resolved and tenant-scoped. Every field after
 * {@code unitId} is optional; leaving them all null asks the walk-in question — the company's own
 * price with no customer in view — which is what the till, the batch price read and every caller
 * that predates this record get.
 *
 * @param companyId           company of the document being priced (tenant scope)
 * @param productId           product on the line
 * @param unitId              the unit the price is expressed in (base unit or a configured pack)
 * @param customerId          the document's customer, or null for a walk-in / no customer. Drives
 *                            customer-specific contract prices.
 * @param customerPriceListId the customer's {@code default_price_list_id}, or null. Used only
 *                            while that list is ACTIVE and effective on {@code businessDate}.
 * @param currency            ISO code of the document; null = any. A row in this currency is
 *                            preferred over one in another currency.
 * @param quantity            the line quantity in {@code unitId}; null = 1. Only consulted for a
 *                            customer price's minimum quantity.
 * @param businessDate        the date validity windows are judged on; null = today
 */
public record SellingPriceQuery(
        Long companyId,
        Long productId,
        Long unitId,
        Long customerId,
        Long customerPriceListId,
        String currency,
        BigDecimal quantity,
        LocalDate businessDate) {

    /** The walk-in question — no customer, any currency, today. */
    public static SellingPriceQuery walkIn(Long companyId, Long productId, Long unitId) {
        return new SellingPriceQuery(companyId, productId, unitId, null, null, null, null, null);
    }
}
