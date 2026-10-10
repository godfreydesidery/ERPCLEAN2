package com.erp.modules.ap.domain.dto;

import java.math.BigDecimal;

/**
 * One creditors-ageing row per supplier and currency (AP-11; mirror of AR's
 * {@code ArCustomerAgeingRowDto}). Amounts are in {@code currency}, net of the supplier's
 * unapplied debit notes; never null.
 */
public record ApSupplierAgeingRowDto(
        Long supplierId,
        String supplierUid,
        String supplierCode,
        String supplierName,
        BigDecimal current,
        BigDecimal days1to30,
        BigDecimal days31to60,
        BigDecimal days61to90,
        BigDecimal days91Plus,
        BigDecimal total,
        String currency
) {}
