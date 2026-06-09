package com.erp.modules.cashbank.domain.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** Request to mark/unmark transactions as cleared in a reconciliation (FR-CASH-13). */
public record MarkClearedRequest(
        /** uids of cash_transactions to mark cleared. */
        @NotEmpty List<String> transactionUids,
        /** true = mark cleared; false = mark uncleared (allowed only while reconciliation is DRAFT). */
        boolean cleared
) {}
