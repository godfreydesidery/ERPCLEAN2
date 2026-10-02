package com.erp.modules.ap.domain.dto;

import com.erp.modules.ap.domain.enums.ApLedgerEntryType;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One line of a supplier statement. Exactly one of {@code debit} / {@code credit} is non-zero;
 * {@code balance} is the running balance after this line (positive = we owe the supplier).
 */
public record ApSupplierLedgerRowDto(
        LocalDate date,
        ApLedgerEntryType type,
        String reference,
        String description,
        BigDecimal debit,
        BigDecimal credit,
        BigDecimal balance
) {}
