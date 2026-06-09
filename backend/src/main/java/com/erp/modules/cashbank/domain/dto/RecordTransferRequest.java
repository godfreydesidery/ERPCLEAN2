package com.erp.modules.cashbank.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Request to record an inter-account transfer (FR-CASH-08). */
public record RecordTransferRequest(
        @NotBlank String companyUid,
        @NotBlank String sourceAccountUid,
        @NotBlank String destinationAccountUid,
        @NotNull @Positive BigDecimal amount,
        @NotNull LocalDate transferDate,
        String reference
) {}
