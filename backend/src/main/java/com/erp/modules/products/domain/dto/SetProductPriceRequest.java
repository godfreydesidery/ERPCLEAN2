package com.erp.modules.products.domain.dto;

import com.erp.platform.common.money.MoneyDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO to set (upsert) a product's price on a named price list (FR-PROD-10/11).
 * {@code unitUid} is optional (ADR-0048 D-1): absent/blank targets the base-unit price row
 * (unit_id IS NULL, back-compatible with pre-D-1 callers); a supplied uid targets a per-unit
 * (pack-specific) price override — it must resolve to the product's base unit (coerced to the
 * base row) or a configured {@code product_bulk_pack} unit.
 */
public record SetProductPriceRequest(
        @NotBlank String priceListUid,
        @NotNull MoneyDto price,
        String unitUid
) {

    /** Base-unit price (back-compat: pre-ADR-0048 callers construct with two args). */
    public SetProductPriceRequest(String priceListUid, MoneyDto price) {
        this(priceListUid, price, null);
    }
}
