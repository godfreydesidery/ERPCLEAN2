package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;

/**
 * One VAT band on the printed GRN foot (Kilimanjaro K9) — goods value and VAT per VAT status.
 *
 * <p>The receipt itself stores no VAT: purchase VAT is a supplier-bill concept and is posted from
 * the bill, never from the receipt. The band shown here is therefore the EXPECTED input VAT, derived
 * from each product's VAT status against the company's configured {@code tax_rates}, so the clerk
 * checking the delivery against the supplier's invoice has something to compare. Where no rate is
 * configured for a status the band prints a zero rather than guessing.
 *
 * @param rate stored fraction (0.18), rendered as a percentage by the document layer
 */
public record GoodsReceiptVatBandDto(
        String     vatStatus,
        BigDecimal rate,
        BigDecimal goodsValue,
        BigDecimal vatAmount
) {}
