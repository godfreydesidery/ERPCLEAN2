package com.erp.modules.gl.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Trial balance for a company (ADR-0013 D-8, FR-GL-16).
 * A correct set of books yields totalDebits == totalCredits (nets to zero).
 */
public record TrialBalanceDto(
        Long companyId,
        List<TrialBalanceRowDto> rows,
        BigDecimal totalDebits,
        BigDecimal totalCredits
) {}
