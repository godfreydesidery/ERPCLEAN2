package com.erp.modules.cashbank.domain.dto;

import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** Response DTO for a petty-cash imprest fund (ADR-0050 D-7 PR-B). */
public record PettyCashFundDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String code,
        String name,
        String custodianUid,
        String custodianName,
        BigDecimal floatAmount,
        BigDecimal balanceAmount,
        String currency,
        MasterStatus status,
        Long version,
        Instant createdAt
) {}
