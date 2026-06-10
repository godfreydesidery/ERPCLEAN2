package com.erp.modules.tax.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Period WHT register: WHT_ON_PAYMENT + WHT_ON_RECEIPT rows + totals (ADR-0017 D-9).
 */
public record WhtRegisterDto(
        Long companyId,
        LocalDate periodStart,
        LocalDate periodEnd,
        List<WhtRegisterRowDto> payableRows,
        BigDecimal totalPayable,
        List<WhtRegisterRowDto> receivableRows,
        BigDecimal totalReceivable
) {}
