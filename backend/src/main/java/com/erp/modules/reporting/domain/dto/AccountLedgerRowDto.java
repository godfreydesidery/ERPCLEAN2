package com.erp.modules.reporting.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row in an account-ledger drill-down — one journal line (ADR-0018 D-3(d), FR-REP-04).
 * {@code runningBalance} is the account balance after applying this line (debit-normal convention).
 */
public record AccountLedgerRowDto(
        LocalDate  postingDate,
        String     sourceType,
        String     sourceRef,
        String     entryUid,
        String     lineMemo,
        BigDecimal debit,
        BigDecimal credit,
        BigDecimal runningBalance
) {}
