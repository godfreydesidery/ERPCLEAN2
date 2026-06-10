package com.erp.modules.reporting.domain.dto;

import java.util.List;

/**
 * Balance Sheet DTO (ADR-0018 D-6, FR-REP-02).
 *
 * <p>Sections: CURRENT_ASSETS, NON_CURRENT_ASSETS, CURRENT_LIABILITIES, NON_CURRENT_LIABILITIES,
 * EQUITY (including the two synthetic equity-fold lines).
 * Self-check: {@link ReconciliationDto} asserts totalAssets == totalLiabilities + totalEquity.
 */
public record BalanceSheetDto(
        StatementHeaderDto        header,
        List<StatementSectionDto> sections,
        AmountPairDto             totalAssets,
        AmountPairDto             totalLiabilities,
        AmountPairDto             totalEquity,
        ReconciliationDto         reconciliation
) {}
