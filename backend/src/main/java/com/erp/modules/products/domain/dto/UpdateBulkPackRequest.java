package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request DTO to correct an existing bulk pack's conversion factor (FR-PROD-06).
 *
 * <p>Only {@code factorToBase} is updatable: the pack's unit is part of the
 * {@code uq_product_bulk_pack_unit} key, so re-pointing a row at another unit is a remove + add,
 * not an edit. A wrong factor, by contrast, is the field operators actually mistype (a CARTON's 48
 * entered on the OUTER row) and until now the only "fix" was to delete and re-add the pack.
 */
public record UpdateBulkPackRequest(
        @NotNull @DecimalMin(value = "0.000001", message = "Conversion factor must be greater than zero.")
        BigDecimal factorToBase
) {
}
