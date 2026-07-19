package com.erp.modules.stock.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.math.BigDecimal;
import java.util.List;

/** Printable current-stock listing with selling price (SAM Electronix go-live). */
public record StockReportDto(
        ReportCompanyHeaderDto   company,
        String                   currency,
        List<StockReportRowDto>  rows,
        BigDecimal               totalValue,
        String                   generatedAt) {
}
