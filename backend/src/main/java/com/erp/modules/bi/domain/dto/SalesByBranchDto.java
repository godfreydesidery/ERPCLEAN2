package com.erp.modules.bi.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sales-by-Branch BI panel payload (ADR-0037).
 *
 * <p>{@code rows} are sorted by {@code total} descending; {@code grandTotal} is the sum of all
 * row totals; {@code invoiceCount} is the sum of all row counts; {@code currency} is the company
 * base currency.
 *
 * <p>Nullable in {@link DashboardDto} — gated by {@code BI.FINANCE.VIEW}.
 */
public record SalesByBranchDto(String currency, BigDecimal grandTotal,
                               long invoiceCount, List<BranchSalesRowDto> rows) {}
