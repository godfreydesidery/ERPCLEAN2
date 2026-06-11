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
 * Request body for creating an approval policy (FR-APR-01, ADR-0022 D-3).
 *
 * <p>If {@code branchScope = BRANCH} then {@code branchUid} must be provided.
 * If {@code branchScope = COMPANY_WIDE} then {@code branchUid} must be null.
 * Validated in service. Amount band: {@code [minAmount, maxAmount)}; maxAmount null = top band.
 */
public record CreateApprovalPolicyRequest(
        @NotNull Long companyId,
        @NotBlank String documentType,
        @NotBlank String name,
        @NotNull PolicyBranchScope branchScope,
        /** UID of the branch — required when branchScope = BRANCH. */
        String branchUid,
        @NotNull @DecimalMin("0") BigDecimal minAmount,
        /** Null = unbounded top band. */
        BigDecimal maxAmount,
        String notes,
        @NotEmpty @Valid List<PolicyStepInputDto> steps
) {}
