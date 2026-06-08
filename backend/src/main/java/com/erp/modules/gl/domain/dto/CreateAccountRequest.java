package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.AccountType;

/** Request DTO to create a chart-of-accounts entry. */
public record CreateAccountRequest(
        String companyUid,
        String accountCode,
        String name,
        AccountType accountType
) {}
