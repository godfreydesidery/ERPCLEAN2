package com.erp.modules.reporting.domain.dto;

import java.util.List;

/**
 * Cash-Flow Statement (indirect method) DTO (ADR-0018 D-7, FR-REP-03).
 *
 * <p>Sections: OPERATING, INVESTING, FINANCING.
 * Self-check: {@link ReconciliationDto} asserts netChangeInCash == Δ(cash-equivalent GL accounts).
 */
public record CashFlowStatementDto(
        StatementHeaderDto        header,
        List<StatementSectionDto> sections,
        AmountPairDto             netChangeInCash,
        AmountPairDto             openingCash,
        AmountPairDto             closingCash,
        ReconciliationDto         reconciliation
) {}
