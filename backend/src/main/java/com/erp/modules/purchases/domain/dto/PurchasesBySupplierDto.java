package com.erp.modules.purchases.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.util.List;

/**
 * Purchases by Supplier — receipts, returns and net purchases per supplier over a period, plus what
 * was billed and is still unpaid when the caller may see supplier bills.
 *
 * @param returnsShown false when the caller lacks the purchase-returns view permission
 * @param billsShown   false when the caller lacks the accounts-payable view permission
 */
public record PurchasesBySupplierDto(
        ReportCompanyHeaderDto           company,
        String                           fromDate,
        String                           toDate,
        String                           branchName,
        String                           currency,
        boolean                          returnsShown,
        boolean                          billsShown,
        List<PurchasesBySupplierRowDto>  rows,
        PurchasesBySupplierTotalsDto     totals,
        String                           generatedAt
) {}
