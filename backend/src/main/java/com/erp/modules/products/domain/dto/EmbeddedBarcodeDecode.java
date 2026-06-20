package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.SymbologyValueKind;
import java.math.BigDecimal;

/**
 * Result of decoding a variable-digit (type-2) barcode using a {@code BarcodeSymbologyRule}
 * (ADR-0044 D-1a / BR-9).
 *
 * @param itemCode   extracted item-code substring (looked up against barcode or product.code
 *                   depending on the rule's {@code itemMatch})
 * @param valueKind  what the decoded value represents (WEIGHT or PRICE)
 * @param value      the decoded value (raw integer / 10^valueDecimals)
 */
public record EmbeddedBarcodeDecode(
        String itemCode,
        SymbologyValueKind valueKind,
        BigDecimal value
) {
}
