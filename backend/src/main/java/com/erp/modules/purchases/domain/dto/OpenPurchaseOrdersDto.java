package com.erp.modules.purchases.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.util.List;

/** Open Purchase Orders — what is still owed by suppliers on placed orders, as at a date. */
public record OpenPurchaseOrdersDto(
        ReportCompanyHeaderDto          company,
        String                          asOfDate,
        String                          branchName,
        String                          supplierName,
        String                          currency,
        List<OpenPurchaseOrderRowDto>   rows,
        OpenPurchaseOrdersTotalsDto     totals,
        String                          generatedAt
) {}
