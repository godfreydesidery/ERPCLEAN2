package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * Per-location on-hand row (ADR-0028 D-8, FR-INVD-05/06).
 *
 * <p>The label fields ({@code locationUid}/{@code locationCode}/{@code locationName} and
 * {@code productUid}/{@code productCode}/{@code productName}/{@code unitLabel}) are enriched by
 * {@code LocationOnHandQuery} — locations from the stock module's own repository, product labels
 * across the module boundary via {@code ProductService} (DTO only, never the entity). Without them
 * a caller receives quantities with nothing to display them against; they are null only when the
 * referenced master has since been deleted.
 *
 * @param unitLabel the product's base-unit label — every quantity on the row is in this unit.
 */
public record LocationOnHandRowDto(
        Long locationId,
        String locationUid,
        String locationCode,
        String locationName,
        Long productId,
        String productUid,
        String productCode,
        String productName,
        String unitLabel,
        BigDecimal quantity,
        BigDecimal onHandValue,
        BigDecimal avgCost,
        String currency
) {}
