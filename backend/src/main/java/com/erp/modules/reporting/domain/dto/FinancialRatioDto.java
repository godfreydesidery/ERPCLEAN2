package com.erp.modules.reporting.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * One financial ratio, with the formula it uses and the statement figures it was computed from.
 *
 * @param key               stable identifier (e.g. {@code CURRENT_RATIO})
 * @param formula           the formula in words, as printed beside the result
 * @param inputs            the statement figures plugged into the formula
 * @param value             the result rounded to 2 dp, or null when it cannot be computed
 * @param unit              {@code "x"} (times), {@code "%"} or {@code "days"}
 * @param unavailableReason why {@code value} is null (a zero denominator), else null
 * @param note              a caveat on how to read this ratio, or null
 */
public record FinancialRatioDto(
        String              key,
        String              name,
        String              formula,
        List<RatioInputDto> inputs,
        BigDecimal          value,
        String              unit,
        String              unavailableReason,
        String              note
) {}
