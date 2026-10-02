package com.erp.modules.reporting.domain.dto;

import java.math.BigDecimal;

/** One named figure a financial ratio was computed from, as read off the statement. */
public record RatioInputDto(String label, BigDecimal amount) {}
