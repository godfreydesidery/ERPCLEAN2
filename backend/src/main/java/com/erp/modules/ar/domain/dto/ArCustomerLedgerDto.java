package com.erp.modules.ar.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A customer statement over a period: balance brought forward, every movement in the period with a
 * running balance, and the closing balance (positive = the customer owes us).
 *
 * <p>One currency per statement. {@code otherCurrencyCount} counts the customer's movements up to
 * {@code toDate} in OTHER currencies, which are left off rather than added to a balance they do not
 * belong to — the export says so at its foot.
 *
 * @param fromDate null when the statement runs from the customer's first transaction
 */
public record ArCustomerLedgerDto(
        ReportCompanyHeaderDto company,
        String customerUid,
        String customerCode,
        String customerName,
        String customerTin,
        String customerVrn,
        LocalDate fromDate,
        LocalDate toDate,
        String currency,
        BigDecimal openingBalance,
        List<ArCustomerLedgerRowDto> rows,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        BigDecimal closingBalance,
        int otherCurrencyCount,
        String generatedAt
) {}
