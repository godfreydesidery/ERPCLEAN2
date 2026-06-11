package com.erp.modules.approvals.domain.dto;

import com.erp.modules.approvals.domain.enums.PolicyBranchScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for editing an approval policy (FR-APR-02, ADR-0022 D-3).
 * Edits affect only future submissions; in-flight requests are unaffected (BR-APR-05).
 */
public record UpdateApprovalPolicyRequest(
        @NotBlank String documentType,
        @NotBlank String name,
        @NotNull PolicyBranchScope branchScope,
        String branchUid,
        @NotNull @DecimalMin("0") BigDecimal minAmount,
        BigDecimal maxAmount,
        String notes,
        @NotNull Boolean active,
        @NotEmpty @Valid List<PolicyStepInputDto> steps
) {}
