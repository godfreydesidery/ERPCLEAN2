package com.erp.modules.tax.domain.dto;

import java.math.BigDecimal;

/**
 * Output breakdown by tax band (ADR-0017 D-1 / D-2b).
 */
public record VatReturnBandDto(
        Long id,
        Long vatReturnId,
        String taxBand,
        BigDecimal taxableBase,
        BigDecimal outputVat
) {}
