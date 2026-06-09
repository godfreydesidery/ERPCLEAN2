package com.erp.modules.ap.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Pay a single bill (fully or partly). */
public record PaySingleBillRequest(
        @NotBlank String companyUid,
        /** uid of the supplier_bill to pay. */
        @NotBlank String supplierBillUid,
        @NotNull @Positive BigDecimal amount,
        @NotNull LocalDate paymentDate,
        @NotBlank String tenderType,
        String bankReference
) {}
