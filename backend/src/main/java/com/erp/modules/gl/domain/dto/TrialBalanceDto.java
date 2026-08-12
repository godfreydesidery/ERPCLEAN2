package com.erp.modules.gl.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.math.BigDecimal;
import java.util.List;

/**
 * Trial balance for a company (ADR-0013 D-8, FR-GL-16).
 * A correct set of books yields totalDebits == totalCredits (nets to zero).
 *
 * <p>Carries the printable letterhead block ({@code company}, {@code baseCurrency},
 * {@code periodLabel}, {@code generatedAt}) alongside the figures, because the trial balance is the
 * first page of a period-close pack and a page of numbers with no company name, no currency and no
 * "as at" is not filable. Same shape the other printable registers use
 * ({@code StockReportDto}, {@code ProductStockReportDto}) so the exporters see one model.
 *
 * <p>{@code company} may be null (the letterhead row could not be read); the figures are still
 * correct and the export simply prints no letterhead — a missing address must never cost the
 * accountant her trial balance.
 */
public record TrialBalanceDto(
        Long companyId,
        ReportCompanyHeaderDto company,
        String baseCurrency,
        String periodLabel,
        List<TrialBalanceRowDto> rows,
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        String generatedAt
) {}
