package com.erp.modules.stock.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.math.BigDecimal;
import java.util.List;

/**
 * Reorder Report — stock lines at or below their reorder level, with the preferred supplier.
 *
 * @param costVisible          whether the caller may see buying prices. False means the cost
 *                             columns were WITHHELD, not that the items are uncosted — the screen
 *                             hides the columns rather than printing blanks that would read as
 *                             "no cost on record"
 * @param estimatedOrderValue  sum of the rows' estimated order values that are known; null when
 *                             cost is hidden
 * @param rowsWithoutCost      rows left out of that sum because the product was never received
 */
public record ReorderReportDto(
        ReportCompanyHeaderDto company,
        String branchName,
        String supplierName,
        String currency,
        boolean costVisible,
        List<ReorderRowDto> rows,
        int itemCount,
        BigDecimal estimatedOrderValue,
        int rowsWithoutCost,
        String generatedAt) {
}
