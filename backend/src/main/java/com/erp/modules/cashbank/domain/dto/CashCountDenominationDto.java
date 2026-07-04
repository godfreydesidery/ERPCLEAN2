package com.erp.modules.cashbank.domain.dto;

import java.math.BigDecimal;

/** Response DTO: one denomination line of a cash count (ADR-0050 D-7 PR-A). */
public record CashCountDenominationDto(
        BigDecimal denomination,
        int quantity,
        BigDecimal lineAmount
) {}
