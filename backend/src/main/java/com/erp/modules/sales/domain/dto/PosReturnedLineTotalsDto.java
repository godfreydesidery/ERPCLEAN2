package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * What has already been returned against one sold invoice line, summed over every
 * {@code pos_sale_return_lines} row for it (ADR-0072). The basis for "remaining" quantity and for the
 * exact-remainder rule on the final return of a line.
 */
public record PosReturnedLineTotalsDto(
        Long salesInvoiceLineId,
        BigDecimal qtyReturned,
        BigDecimal qtyReturnedBase,
        BigDecimal netAmount,
        BigDecimal discountAmount,
        BigDecimal vatAmount,
        BigDecimal grossAmount
) {
}
