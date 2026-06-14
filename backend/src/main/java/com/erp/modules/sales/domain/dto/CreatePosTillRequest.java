package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to register a new POS till on a branch (ADR-0029 D-5).
 *
 * <p>{@code cashBankAccountUid} is optional. When absent, the service defaults to the
 * company's default active cash/bank account (the one with {@code is_default = true}).
 */
public record CreatePosTillRequest(
        @NotBlank String companyUid,
        @NotNull  Long   branchId,
        @NotBlank @Size(max = 60) String name,
        String cashBankAccountUid   // nullable — defaults to company's default cash account
) {}
