package com.erp.modules.tax.domain.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Output of a VAT return computation read (ADR-0017 D-6).
 * byBand: tax_band → {taxableBase, outputVat}; totalOutput; totalInput.
 */
public record VatReturnComputationDto(
        Map<String, BandTotalsDto> byBand,
        BigDecimal totalOutput,
        BigDecimal totalInput
) {
    /** Per-band taxable base + output VAT totals. */
    public record BandTotalsDto(
            BigDecimal taxableBase,
            BigDecimal outputVat
    ) {}
}
