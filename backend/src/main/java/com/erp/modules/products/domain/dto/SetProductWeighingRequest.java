package com.erp.modules.products.domain.dto;

import java.math.BigDecimal;

/**
 * Request DTO to configure a product's weighed-goods (sell-by-weight) profile (ADR-0044 D-1b).
 * Set via {@code POST /api/v1/products/uid/{uid}/weighing} — kept as a dedicated sub-resource
 * mutation (like set-price / bulk-packs / barcodes) rather than widening the create/update record.
 *
 * <p>{@code tareWeight}/{@code scaleStep} ranges are validated in the service with friendly messages
 * (not bean-validation annotations, which would prefix the raw field name into the user-facing error).
 *
 * @param weighed     product is sold by weight. When true the service requires the product's base
 *                    unit to be a WEIGHT unit (e.g. kilograms).
 * @param tareWeight  optional container/tare weight in the base weight unit (≥ 0). Configuration for
 *                    the weighing client; the server does not auto-deduct it (ADR-0044 D-7).
 * @param scaleStep   optional scale division (&gt; 0), e.g. {@code 0.005} kg. When set, the sale
 *                    path rounds the weighed quantity to the nearest multiple. Null = no rounding.
 * @param maxSaleWeight optional safety ceiling (&gt; 0) in the base weight unit. A weighed line above
 *                    this weight is rejected — catches a kg/gram fat-finger. Null = no ceiling.
 */
public record SetProductWeighingRequest(
        boolean weighed,
        BigDecimal tareWeight,
        BigDecimal scaleStep,
        BigDecimal maxSaleWeight
) {
    /** Back-compatible 3-arg form (no max-weight ceiling). */
    public SetProductWeighingRequest(boolean weighed, BigDecimal tareWeight, BigDecimal scaleStep) {
        this(weighed, tareWeight, scaleStep, null);
    }
}
