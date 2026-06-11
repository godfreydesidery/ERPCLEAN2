package com.erp.modules.approvals.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Input to {@code ApprovalEngine.submitForApproval} — the integration hook input (ADR-0022 D-7).
 *
 * <p>The consumer passes its document reference (opaque type + uid), the amount to match against
 * policies, the originating branch (by uid), and an optional human summary for the inbox. The
 * engine does not validate the amount against the consumer's document (BR-APR-10).
 *
 * <p>This is a DTO-only surface — the engine imports no consumer entity (D-12).
 */
public record SubmitForApprovalRequest(
        @NotBlank String documentType,
        @NotBlank String documentUid,
        @NotNull @DecimalMin("0") BigDecimal amount,
        /** Base currency code (e.g. TZS). */
        @NotBlank String currency,
        @NotNull Long companyId,
        @NotBlank String branchUid,
        @NotNull Long submittedByUserId,
        /** Human label for the inbox ("PO to Acme Ltd — Ref 12345"). Optional but recommended. */
        String summary
) {}
