package com.erp.modules.ar.domain.dto;

import com.erp.modules.ar.domain.enums.ArLedgerEntryType;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One line of a customer statement. Exactly one of {@code debit} / {@code credit} is non-zero;
 * {@code balance} is the running balance after this line (positive = the customer owes us).
 */
public record ArCustomerLedgerRowDto(
        LocalDate date,
        ArLedgerEntryType type,
        String reference,
        String description,
        BigDecimal debit,
        BigDecimal credit,
        BigDecimal balance
) {}
