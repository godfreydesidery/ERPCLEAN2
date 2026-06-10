package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * VAT output totals for a period, by tax band — returned by
 * {@code SalesInvoiceService.findVatSummaryForPeriod} for the tax module's computation
 * reader (ADR-0017 D-6 / D-10). Sales-owned DTO; tax module imports this, never a Sales entity.
 */
public record VatOutputSummaryDto(
        /** Tax band → {taxableBase, outputVat}. Key = band code e.g. "STANDARD", "ZERO_RATED", "EXEMPT". */
        Map<String, BandTotalsDto> byBand,
        /** Σ outputVat across all bands. */
        BigDecimal totalOutputVat
) {
    /** Per-band sums. */
    public record BandTotalsDto(
            BigDecimal taxableBase,
            BigDecimal outputVat
    ) {}
}
