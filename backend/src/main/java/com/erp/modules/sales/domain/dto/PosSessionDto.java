package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.PosSessionStatus;
import java.math.BigDecimal;

/**
 * Response DTO for a POS session (ADR-0029 D-5).
 *
 * <p><b>The name fields answer "which one am I looking at?"</b> (UAT, 2026-08). This record used to
 * carry {@code cashierId: "7"}, {@code posTillId: "1"} and {@code branchId: "1"} and nothing else,
 * so no shift could be read at a glance and every review needed three cross-reference lookups.
 * {@code cashierName}, {@code tillName} and {@code branchName} put the labels beside the ids —
 * the ids stay because they are what other calls are addressed by.
 *
 * <p>Resolution follows {@code PosTillDto.openSessionCashierName}: names come from a BATCH lookup
 * ({@code UserLookupService.displayNamesByIds} for the cashier, one repository read per id set for
 * tills and branches), keyed off ids read from the already-scoped session rows. A cashier IAM can no
 * longer name falls back to a neutral phrase, never an id; a till or branch row that cannot be
 * resolved leaves its name {@code null} and renders as "—".
 */
public record PosSessionDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String branchName,
        Long posTillId,
        String tillName,
        Long cashierId,
        String cashierName,
        String sessionNumber,
        PosSessionStatus status,
        String openedAt,
        String closedAt,
        String reconciledAt,
        BigDecimal openingFloatAmount,
        BigDecimal countedCashAmount,
        BigDecimal expectedCashAmount,
        BigDecimal varianceAmount,
        Long varianceJournalId,
        String notes
) {}
