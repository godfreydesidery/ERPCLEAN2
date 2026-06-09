package com.erp.modules.ap.domain.dto;

import java.math.BigDecimal;

public record ApBalanceDto(
        Long companyId,
        Long supplierId,
        BigDecimal outstandingBalance,
        String currency
) {}
