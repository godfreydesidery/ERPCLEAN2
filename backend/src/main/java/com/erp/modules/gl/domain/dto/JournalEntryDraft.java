package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.JournalSourceType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Internal draft passed to GLPostingService.post(...) — not a REST DTO.
 * Used by manual journal path and by event handlers alike so the same engine applies invariants.
 */
public record JournalEntryDraft(
        Long companyId,
        Long branchId,
        LocalDate postingDate,
        String description,
        JournalSourceType sourceType,
        String sourceRef,
        Long reversalOfId,
        Long postedBy,   // NULL for SYSTEM auto-poster
        List<LineDraft> lines
) {
    /** One draft line — account resolved to id before being passed to the engine. */
    public record LineDraft(
            Long accountId,
            BigDecimal debitAmount,
            BigDecimal creditAmount,
            String currency,
            String lineMemo
    ) {}
}
