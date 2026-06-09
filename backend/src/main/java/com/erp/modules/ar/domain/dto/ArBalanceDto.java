package com.erp.modules.ar.domain.dto;

import java.math.BigDecimal;

/**
 * Current AR balance for a customer (ADR-0014 D-9).
 * balance = Σ outstanding_amount (OPEN/PARTIAL) − Σ unallocated_amount.
 * Sales reads this DTO at finalise to check the credit limit (D-10, FR-AR-19).
 */
public record ArBalanceDto(
        Long companyId,
        Long customerId,
        BigDecimal balance,
        String currency
) {}
