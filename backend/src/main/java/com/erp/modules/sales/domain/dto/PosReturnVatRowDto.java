package com.erp.modules.sales.domain.dto;

import com.erp.modules.products.domain.enums.VatStatus;
import java.math.BigDecimal;

/**
 * One row of the VAT-return view of POS item returns (ADR-0072): the net and VAT given back in a
 * period for one tax band at one stamped exchange rate. Sales-internal; folded into the void summary
 * so output VAT falls by exactly what the return's SALES_REVERSAL journal debited.
 */
public record PosReturnVatRowDto(
        VatStatus vatStatus,
        BigDecimal fxRate,
        BigDecimal net,
        BigDecimal vat
) {
}
