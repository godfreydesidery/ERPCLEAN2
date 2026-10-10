package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.StatutoryLiability;
import java.math.BigDecimal;

/**
 * ACC-07: what is still owed on one payroll statutory liability — the credit balance of its control
 * account in the general ledger (all payroll postings less all payments), in base currency.
 */
public record StatutoryLiabilityBalanceDto(
        StatutoryLiability liability,
        String accountCode,
        String accountName,
        BigDecimal outstanding
) {}
