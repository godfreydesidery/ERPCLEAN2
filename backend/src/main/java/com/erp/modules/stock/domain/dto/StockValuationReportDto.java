package com.erp.modules.stock.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.math.BigDecimal;
import java.util.List;

/**
 * Stock valuation report + GL reconciliation bar (ADR-0020 D-6, FR-INV-07).
 *
 * <p>The {@code recon} bar asserts {@code Σ(on_hand_value) == GL 1300 Inventory balance}.
 * {@code recon.ties() == false} is a finance-grade defect surfaced for investigation (BR-INV-06).
 *
 * <p>{@code company} and {@code generatedAt} carry the printed letterhead for the PDF/XLSX/CSV
 * export, the same pair {@link StockReportDto} and {@link ProductStockReportDto} already carry.
 * {@code company} is null only when the company row cannot be read — the export then prints without
 * a letterhead rather than failing.
 */
public record StockValuationReportDto(
        Long                       companyId,
        ReportCompanyHeaderDto     company,
        List<StockValuationRowDto> rows,
        BigDecimal                 totalValue,
        StockValuationReconDto     recon,
        String                     currency,
        String                     generatedAt
) {}
