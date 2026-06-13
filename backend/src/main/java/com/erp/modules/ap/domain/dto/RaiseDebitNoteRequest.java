package com.erp.modules.ap.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

public record RaiseDebitNoteRequest(
        @NotBlank String companyUid,
        @NotBlank String supplierUid,
        /** Optional: the bill whose outstanding is reduced. Null = general supplier credit. */
        String supplierBillUid,
        @NotNull LocalDate noteDate,
        @NotNull @Positive BigDecimal netAmount,
        BigDecimal vatAmount,
        @NotBlank String reason,
        /** Optional — set by internal callers (e.g. PURCHASE_RETURN:{uid}). Null for manual notes. */
        String origin
) {}
