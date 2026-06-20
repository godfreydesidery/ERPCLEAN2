package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.SymbologyItemMatch;
import com.erp.modules.products.domain.enums.SymbologyValueKind;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Request DTO to update a {@code BarcodeSymbologyRule} (ADR-0044 D-1a / BR-9).
 * Code and companyId are immutable once set; all other fields may be changed.
 */
public record UpdateBarcodeSymbologyRuleRequest(
        @NotBlank String name,
        @NotBlank @Size(min = 1, max = 8) String prefix,
        @NotNull SymbologyValueKind valueKind,
        @PositiveOrZero short itemCodeStart,
        @Positive short itemCodeLength,
        @PositiveOrZero short valueStart,
        @Positive short valueLength,
        @PositiveOrZero short valueDecimals,
        @NotNull SymbologyItemMatch itemMatch,
        String checkDigitMode,
        @Min(1) int priority
) {
}
