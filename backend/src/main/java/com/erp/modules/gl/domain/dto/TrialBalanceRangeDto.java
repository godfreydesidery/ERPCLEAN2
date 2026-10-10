package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.NormalBalance;
import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Trial balance "as at" a date, optionally for a date range and one branch (ACC-14): opening
 * balance (everything before {@code from}), movement ({@code from}..{@code asAt}) and closing
 * balance (everything up to {@code asAt}) per account. Opening and closing are shown on their
 * natural side (a credit balance in the credit column); movement keeps both sides.
 *
 * @param from        first day of the movement window; null = from the first posting (opening 0)
 * @param asAt        last day included
 * @param branchUid   the branch the figures are limited to; null = whole company. A branch trial
 *                    balance excludes company-level journals (those posted without a branch)
 * @param branchName  that branch's name, for the page head
 * @param periodLabel ASCII label for print, e.g. "As at 2026-10-31" or "2026-10-01 to 2026-10-31"
 */
public record TrialBalanceRangeDto(
        Long companyId,
        ReportCompanyHeaderDto company,
        String baseCurrency,
        LocalDate from,
        LocalDate asAt,
        String branchUid,
        String branchName,
        String periodLabel,
        List<Row> rows,
        BigDecimal openingDebit,
        BigDecimal openingCredit,
        BigDecimal movementDebit,
        BigDecimal movementCredit,
        BigDecimal closingDebit,
        BigDecimal closingCredit,
        String generatedAt
) {

    /** One account's opening, movement and closing. */
    public record Row(
            Long accountId,
            String accountUid,
            String accountCode,
            String accountName,
            AccountType accountType,
            NormalBalance normalBalance,
            BigDecimal openingDebit,
            BigDecimal openingCredit,
            BigDecimal movementDebit,
            BigDecimal movementCredit,
            BigDecimal closingDebit,
            BigDecimal closingCredit
    ) {}
}
