package com.erp.modules.approvals.domain.dto;

import com.erp.modules.approvals.domain.enums.PolicyBranchScope;
import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;
import java.util.List;

/**
 * Response DTO for an approval policy (ADR-0022 D-3).
 * Both id (Long, serialised as string by Jackson config) and uid are exposed.
 */
public record ApprovalPolicyDto(
        Long id,
        String uid,
        Long companyId,
        String documentType,
        String name,
        PolicyBranchScope branchScope,
        Long branchId,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        String currency,
        boolean active,
        MasterStatus status,
        String notes,
        List<ApprovalPolicyStepDto> steps
) {}
