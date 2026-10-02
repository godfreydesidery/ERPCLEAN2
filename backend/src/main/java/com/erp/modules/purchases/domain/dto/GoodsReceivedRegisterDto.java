package com.erp.modules.purchases.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.util.List;

/**
 * Goods Received Register — every goods-receipt line over a period (one page of it).
 *
 * <p>{@code totals} cover the whole matching set, so the footer does not move as the user pages.
 */
public record GoodsReceivedRegisterDto(
        ReportCompanyHeaderDto              company,
        String                              fromDate,
        String                              toDate,
        String                              branchName,
        String                              supplierName,
        String                              productName,
        String                              currency,
        List<GoodsReceivedRegisterRowDto>   rows,
        GoodsReceivedRegisterTotalsDto      totals,
        int                                 page,
        int                                 size,
        long                                totalElements,
        int                                 totalPages,
        String                              generatedAt
) {}
