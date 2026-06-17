package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.NormalBalance;

/** Response DTO for a chart-of-accounts entry (ADR-0013 D-1). */
public record AccountDto(
        Long id,
        String uid,
        Long companyId,
        String accountCode,
        String name,
        AccountType accountType,
        NormalBalance normalBalance,
        boolean active,
        boolean allowManualPosting,
        String status
) {}
