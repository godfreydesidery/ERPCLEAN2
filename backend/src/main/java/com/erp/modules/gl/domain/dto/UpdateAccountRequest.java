package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.AccountType;

/** Request DTO to update a chart-of-accounts entry. */
public record UpdateAccountRequest(
        String name,
        AccountType accountType,
        Boolean active,
        Boolean allowManualPosting
) {}
