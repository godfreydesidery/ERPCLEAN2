package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Items the kitchen marks ready (or recalls). An item's add-ons go with it, so the display sends
 * the item uids only.
 */
public record KitchenBumpRequest(@NotEmpty List<String> lineUids) {}
