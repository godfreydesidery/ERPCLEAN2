package com.erp.modules.sales.domain.dto;

import com.erp.platform.common.domain.MasterStatus;

/**
 * Response DTO for a POS till (ADR-0029 D-5).
 */
public record PosTillDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String code,
        String name,
        Long cashBankAccountId,
        MasterStatus status
) {}
