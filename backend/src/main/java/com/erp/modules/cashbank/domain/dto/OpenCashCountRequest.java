package com.erp.modules.cashbank.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Request to open an end-of-day cash count against a CASH till (ADR-0050 D-7 PR-A). */
public record OpenCashCountRequest(
        @NotBlank String companyUid,
        @NotBlank String cashBankAccountUid,
        @NotNull LocalDate businessDate
) {}
