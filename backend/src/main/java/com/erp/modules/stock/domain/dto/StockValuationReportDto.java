package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Stock valuation report + GL reconciliation bar (ADR-0020 D-6, FR-INV-07).
 *
 * <p>The {@code recon} bar asserts {@code Σ(on_hand_value) == GL 1300 Inventory balance}.
 * {@code recon.ties() == false} is a finance-grade defect surfaced for investigation (BR-INV-06).
 */
public record StockValuationReportDto(
        Long                       companyId,
        List<StockValuationRowDto> rows,
        BigDecimal                 totalValue,
        StockValuationReconDto     recon,
        String                     currency
) {}
