package com.erp.modules.fx.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Wire shape for a currency_rates row (ADR-0036 D-2/D-7).
 *
 * <p>Rate direction: {@code rate} = units of {@code toCurrency} (base) per ONE unit of
 * {@code fromCurrency} (foreign). {@code baseAmount = faceAmount × rate}.
 */
public record CurrencyRateDto(
        Long        id,
        String      uid,
        Long        companyId,
        String      fromCurrency,
        String      toCurrency,
        BigDecimal  rate,
        LocalDate   effectiveDate,
        String      rateType,
        String      source,
        boolean     active
) {}
