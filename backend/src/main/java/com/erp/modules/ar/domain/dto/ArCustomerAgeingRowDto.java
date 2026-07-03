package com.erp.modules.ar.domain.dto;

import java.math.BigDecimal;

/**
 * One ageing row per customer (CFO credit-limit view; distinct from the per-bucket
 * {@link ArAgeingRowDto} used by the company-wide summary). All bucket amounts default to
 * {@link BigDecimal#ZERO} — never null.
 */
public record ArCustomerAgeingRowDto(
        Long customerId,
        String customerCode,
        String customerName,
        BigDecimal current,
        BigDecimal days1to30,
        BigDecimal days31to60,
        BigDecimal days61to90,
        BigDecimal days91Plus,
        BigDecimal total,
        String currency
) {}
