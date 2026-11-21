package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Everything the till needs on its "Return items" screen for one POS sale (ADR-0072): the returns
 * already made, and per sold line how much was sold, returned and is still returnable.
 *
 * <p>{@code returnable} is false when the sale is not eligible (reversed, not a POS sale, its session
 * is closed, nothing left) and {@code returnBlockedReason} then carries the friendly sentence to
 * show instead of the button. It is advisory — {@code POST .../returns} re-checks everything.
 */
public record PosSaleReturnsDto(
        String invoiceUid,
        String invoiceNumber,
        boolean hasReturns,
        BigDecimal returnedGross,
        boolean returnable,
        String returnBlockedReason,
        List<SoldLineDto> lines,
        List<PosSaleReturnDto> returns
) {

    /** A sold line with its returned and remaining quantity (all in the sold line's unit). */
    public record SoldLineDto(
            String invoiceLineUid,
            short lineNo,
            Long productId,
            String productCode,
            String productName,
            String unitName,
            BigDecimal qtySold,
            BigDecimal qtyReturned,
            BigDecimal qtyRemaining,
            BigDecimal grossAmount,
            BigDecimal grossReturned
    ) {
    }
}
