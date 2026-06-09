package com.erp.modules.ar.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Request to enter an AR opening balance at go-live (FR-AR-15). */
public record SetOpeningBalanceRequest(
        String companyUid,
        String customerUid,
        BigDecimal amount,
        String currency,
        LocalDate invoiceDate,
        LocalDate dueDate,
        String documentNo
) {}
