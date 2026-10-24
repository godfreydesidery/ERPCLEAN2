package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.RestaurantOrderType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The bill (ADR-0065 D-7): NOT a receipt and non-fiscal. Priced by the same rules and arithmetic
 * the sale will use, so {@code grossTotal} is what payment charges. Identical items across rounds
 * are combined into one line. {@code printCount} above 1 means the till prints "REPRINT n".
 *
 * @param unsentLineCount items on the bill that never went to a prep station — the till may ask
 *                        "send first?"
 */
public record RestaurantBillDto(
        String orderUid,
        String orderNumber,
        RestaurantOrderType orderType,
        String tableName,
        Short guestCount,
        String serverName,
        Instant billedAt,
        int printCount,
        String currency,
        List<Line> lines,
        BigDecimal netTotal,
        BigDecimal vatTotal,
        BigDecimal grossTotal,
        int unsentLineCount
) {
    /** {@code unitPrice} and {@code amount} are VAT-inclusive. */
    public record Line(String productName, String unitName, BigDecimal quantity,
                       BigDecimal unitPrice, BigDecimal amount) {}
}
