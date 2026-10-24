package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Adds an item to an order (ADR-0065 D-5). Tapping the same plain item again adds to the existing
 * unsent line instead of creating a second one. {@code quantity} null means 1.
 *
 * <p>Phase 3 (ADR-0066): {@code modifierOptionUids} are the choices made on the item (checked
 * against its groups' rules); {@code course} null means 1; {@code held} keeps it back from Send
 * until it is fired.
 */
public record AddRestaurantOrderLineRequest(
        @NotNull Long productId,
        @NotNull Long unitId,
        @DecimalMin("0.0001") BigDecimal quantity,
        @Size(max = 200) String note,
        List<String> modifierOptionUids,
        @Min(1) @Max(9) Short course,
        Boolean held
) {

    /** A plain item: no choices, first course, not held. */
    public AddRestaurantOrderLineRequest(Long productId, Long unitId, BigDecimal quantity, String note) {
        this(productId, unitId, quantity, note, null, null, null);
    }
}
