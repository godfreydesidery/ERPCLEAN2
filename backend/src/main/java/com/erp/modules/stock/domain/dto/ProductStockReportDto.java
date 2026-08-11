package com.erp.modules.stock.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.util.List;

/**
 * Envelope for both product-stock reports.
 *
 * <p>{@code totals} is null for the Product List (a reference listing, where a grand total of
 * unrelated items means nothing) and populated for the Stock Value report.
 *
 * <p>{@code priceListName} and {@code priceIncludesVat} travel with the rows on purpose: "selling
 * price" is meaningless to a shopkeeper without knowing whether VAT is already in it. The report
 * states the stance rather than grossing the figure up silently.
 */
public record ProductStockReportDto(
        ReportCompanyHeaderDto company,
        /** Branch the stock was counted at; null means every branch in the company. */
        String branchName,
        /** Supplier filter applied; null means all suppliers. */
        String supplierName,
        String currency,
        /** The price list the selling prices came from; null when the company has no default. */
        String priceListName,
        boolean priceIncludesVat,
        List<ProductStockRowDto> rows,
        /** Null on the Product List; present on the Stock Value report. */
        ProductStockTotalsDto totals,
        String generatedAt) {
}
