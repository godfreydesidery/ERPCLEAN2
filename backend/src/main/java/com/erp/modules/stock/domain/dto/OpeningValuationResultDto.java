package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * Result of setting the opening inventory valuation for one product (ADR-0020 D-5b).
 */
public record OpeningValuationResultDto(
        String     productUid,
        BigDecimal quantity,
        BigDecimal openingCost,
        BigDecimal openingValue,
        String     glEntryUid,   // uid of the posted DR 1300 / CR 3100 journal entry
        String     currency
) {}
