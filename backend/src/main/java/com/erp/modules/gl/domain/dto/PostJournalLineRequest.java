package com.erp.modules.gl.domain.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/**
 * One line in a PostJournalRequest. Exactly one of debitAmount/creditAmount must be positive;
 * the other zero or null (service-validated, BR-GL-08).
 *
 * <p>{@code @Digits} guards match the journal_lines column (NUMERIC 19,4): integer digits ≤ 15,
 * fraction digits ≤ 4. A value of 1e15 exceeds 15 integer digits and is rejected as 400 before
 * it can cause a DB numeric-overflow 500 (issue #28).
 */
public record PostJournalLineRequest(
        String accountUid,
        @PositiveOrZero
        @Digits(integer = 15, fraction = 4)
        BigDecimal debitAmount,
        @PositiveOrZero
        @Digits(integer = 15, fraction = 4)
        BigDecimal creditAmount,
        String lineMemo
) {}
