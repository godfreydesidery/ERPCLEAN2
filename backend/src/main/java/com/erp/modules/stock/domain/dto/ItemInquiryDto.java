package com.erp.modules.stock.domain.dto;

import java.util.List;

/**
 * The answer to one counter lookup (K-2026-08-30 #3, "Item Inquiry").
 *
 * <p>Deliberately not a report: no company letterhead, no export, no totals. It answers "what is
 * this item, what does it cost us, what do we sell it for, and how many are left" in one call so
 * the person at the counter does not open three screens.
 *
 * @param branchName     the branch the quantities are for; null means every branch in the company
 * @param currency       the company base currency both prices are in
 * @param priceListName  which list the selling prices came from; null when the company has none set
 *                       — the screen then says the price is unknown rather than showing a blank
 * @param priceIncludesVat whether that list's prices are VAT-inclusive, so the column can be labelled
 *                       honestly instead of leaving the reader to guess
 * @param costVisible    false when the caller may not see cost. This is what separates "we have
 *                       never costed this" (a null price with {@code costVisible} true) from "you
 *                       are not allowed to see it" — without the flag a cashier would read an
 *                       ordinary item as uncosted and report it as a data problem
 * @param truncated      true when more items matched than were returned; the screen asks for a
 *                       narrower search rather than implying the list is everything
 * @param rows           the matches, by code
 */
public record ItemInquiryDto(
        String                  branchName,
        String                  currency,
        String                  priceListName,
        boolean                 priceIncludesVat,
        boolean                 costVisible,
        boolean                 truncated,
        List<ItemInquiryRowDto> rows
) {
}
