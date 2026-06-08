package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;

/**
 * The single GL posting engine that both manual and automatic posting paths go through
 * (ADR-0013 D-3, gl.md §3.2). Validates and writes; correction is a reversing entry (BR-GL-02).
 */
public interface GLPostingService {

    /**
     * Validates the draft and writes the batch + entry + lines atomically (BR-GL-01/03/04/05/06).
     * Rejects if: fewer than 2 lines, unbalanced, closed/missing period, inactive or cross-company
     * account, wrong currency. Nothing partial is written on rejection.
     *
     * @return the posted entry DTO
     */
    JournalEntryDto post(JournalEntryDraft draft);

    /**
     * Posts a reversing entry for the given original entry uid (BR-GL-11, ADR-0013 D-3).
     * Swaps debit/credit of every line; sets reversal_of_id; posts into the current open period
     * unless overridden via the draft. Balanced by construction.
     *
     * @param originalEntryUid the uid of the entry to reverse
     * @param reversalDate     the business date for the reversing entry
     * @param sourceType       SALES_REVERSAL or MANUAL (for a manual correction)
     * @param sourceRef        the source doc ref (e.g. invoice uid for void)
     * @param postedBy         NULL for SYSTEM auto-poster
     * @return the posted reversal entry DTO
     */
    JournalEntryDto postReversal(String originalEntryUid, java.time.LocalDate reversalDate,
                                  com.erp.modules.gl.domain.enums.JournalSourceType sourceType,
                                  String sourceRef, Long postedBy);
}
