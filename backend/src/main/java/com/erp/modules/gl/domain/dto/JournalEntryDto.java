package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.JournalSourceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Response DTO for a journal entry (with its lines). */
public record JournalEntryDto(
        Long id,
        String uid,
        Long companyId,
        String batchNumber,
        LocalDate postingDate,
        Long fiscalPeriodId,
        String description,
        JournalSourceType sourceType,
        String sourceRef,
        Long reversalOfId,
        /** Self-FK to the entry that reversed this one (P2-M1). Null if not reversed. */
        Long reversedByEntryId,
        /** True when a reversing entry exists for this entry (P2-M1). */
        boolean reversed,
        /** Informational source-document currency; ledger stays base-only (P2-M1). */
        String headerCurrency,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        Instant postedAt,
        List<JournalLineDto> lines,
        /**
         * Human-readable number of the source document (GRN-0007, INV-0453…) when the posting's
         * description carries it, or a sibling journal of the same source does (ACC-19). Null when
         * unknown — the UI then falls back to the source ref. Display-only.
         */
        String documentRef
) {

    /** Pre-ACC-19 shape (no document ref) — kept so existing callers compile unchanged. */
    public JournalEntryDto(Long id, String uid, Long companyId, String batchNumber,
                           LocalDate postingDate, Long fiscalPeriodId, String description,
                           JournalSourceType sourceType, String sourceRef, Long reversalOfId,
                           Long reversedByEntryId, boolean reversed, String headerCurrency,
                           BigDecimal totalDebit, BigDecimal totalCredit, Instant postedAt,
                           List<JournalLineDto> lines) {
        this(id, uid, companyId, batchNumber, postingDate, fiscalPeriodId, description, sourceType,
                sourceRef, reversalOfId, reversedByEntryId, reversed, headerCurrency, totalDebit,
                totalCredit, postedAt, lines, null);
    }

    /** A copy carrying a document ref. */
    public JournalEntryDto withDocumentRef(String ref) {
        return new JournalEntryDto(id, uid, companyId, batchNumber, postingDate, fiscalPeriodId,
                description, sourceType, sourceRef, reversalOfId, reversedByEntryId, reversed,
                headerCurrency, totalDebit, totalCredit, postedAt, lines, ref);
    }
}
