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
 * Request DTO to create a new {@code BarcodeSymbologyRule} (ADR-0044 D-1a / BR-9).
 *
 * @param companyUid    uid of the owning company
 * @param code          per-company unique rule code
 * @param name          human-readable rule name
 * @param prefix        leading digits that identify this rule (e.g. "21")
 * @param valueKind     WEIGHT or PRICE
 * @param itemCodeStart 0-based start offset of the item-code field in the barcode
 * @param itemCodeLength number of characters in the item-code field (must be &gt; 0)
 * @param valueStart    0-based start offset of the embedded value field
 * @param valueLength   number of characters in the embedded value field (must be &gt; 0)
 * @param valueDecimals implied decimal places (0 = integer; 3 = divide raw by 1000)
 * @param itemMatch     BARCODE or PRODUCT_CODE (how the item code resolves to a product)
 * @param checkDigitMode optional label check-digit mode; null = none (v1)
 * @param priority      evaluation priority (lower = higher precedence); default 100
 */
public record CreateBarcodeSymbologyRuleRequest(
        @NotBlank String companyUid,
        @NotBlank String code,
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
    /** Convenience compact constructor defaulting optional fields. */
    public CreateBarcodeSymbologyRuleRequest(String companyUid, String code, String name,
                                             String prefix, SymbologyValueKind valueKind,
                                             short itemCodeStart, short itemCodeLength,
                                             short valueStart, short valueLength,
                                             short valueDecimals) {
        this(companyUid, code, name, prefix, valueKind,
             itemCodeStart, itemCodeLength, valueStart, valueLength,
             valueDecimals, SymbologyItemMatch.BARCODE, null, 100);
    }
}
