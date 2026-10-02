package com.erp.modules.purchases.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.util.List;

/**
 * Purchase Price Variance — receipt lines whose receipt cost or bill price differs from the PO price.
 *
 * @param billsShown false when the caller lacks the accounts-payable view permission; the bill
 *                   columns are then null and only receipt-vs-order differences are listed
 */
public record PurchasePriceVarianceDto(
        ReportCompanyHeaderDto              company,
        String                              fromDate,
        String                              toDate,
        String                              branchName,
        String                              supplierName,
        String                              currency,
        boolean                             billsShown,
        List<PurchasePriceVarianceRowDto>   rows,
        PurchasePriceVarianceTotalsDto      totals,
        String                              generatedAt
) {}
