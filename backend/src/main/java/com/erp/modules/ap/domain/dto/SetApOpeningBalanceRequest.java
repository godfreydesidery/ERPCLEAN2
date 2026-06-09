package com.erp.modules.ap.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Enter an AP opening balance for a supplier (AP.OPENING.SET permission). */
public record SetApOpeningBalanceRequest(
        @NotBlank String companyUid,
        @NotBlank String supplierUid,
        @NotNull @Positive BigDecimal grossAmount,
        String currency,
        @NotNull LocalDate billDate,
        @NotNull LocalDate dueDate,
        /** Supplier's own invoice/reference number for the opening balance. */
        String supplierInvoiceNo
) {}
