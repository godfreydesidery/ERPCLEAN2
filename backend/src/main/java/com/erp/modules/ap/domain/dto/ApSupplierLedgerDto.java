package com.erp.modules.ap.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A supplier statement over a period: balance brought forward, every movement in the period with a
 * running balance, and the closing balance (positive = we owe the supplier).
 *
 * <p>One currency per statement. {@code otherCurrencyCount} counts the supplier's movements up to
 * {@code toDate} in OTHER currencies, which are left off rather than added to a balance they do not
 * belong to — the export says so at its foot.
 *
 * @param fromDate null when the statement runs from the supplier's first transaction
 */
public record ApSupplierLedgerDto(
        ReportCompanyHeaderDto company,
        String supplierUid,
        String supplierCode,
        String supplierName,
        String supplierTin,
        String supplierVrn,
        LocalDate fromDate,
        LocalDate toDate,
        String currency,
        BigDecimal openingBalance,
        List<ApSupplierLedgerRowDto> rows,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        BigDecimal closingBalance,
        int otherCurrencyCount,
        String generatedAt
) {}
