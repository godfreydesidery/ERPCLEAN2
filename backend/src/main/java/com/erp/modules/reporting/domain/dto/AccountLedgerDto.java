package com.erp.modules.reporting.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Account-ledger drill-down DTO (ADR-0018 D-3(d), FR-REP-04).
 *
 * <p>Contains: opening balance (as-at fromDate-1), each journal line with running balance,
 * and the closing balance (as-at toDate). Paginated — page/size/totalElements exposed.
 */
public record AccountLedgerDto(
        StatementHeaderDto        header,
        Long                      accountId,
        String                    accountUid,
        String                    accountCode,
        String                    accountName,
        BigDecimal                openingBalance,
        List<AccountLedgerRowDto> rows,
        BigDecimal                closingBalance,
        int                       page,
        int                       size,
        long                      totalElements
) {}
