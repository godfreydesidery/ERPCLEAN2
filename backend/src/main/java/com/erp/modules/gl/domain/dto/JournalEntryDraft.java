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
    /**
     * One draft line — account resolved to id before being passed to the engine.
     *
     * <p>ADR-0025 D-4: the four {@code *ValueId} fields are optional dimension tags
     * (already resolved by the poster via {@code costing.DimensionResolver} before building the
     * draft). null = untagged for that slot. Existing callers use the 5-arg convenience
     * constructor which defaults all four to null — so no existing poster's call site changes
     * (NFR-CC-01, the zero-regression guarantee).
     */
    public record LineDraft(
            Long accountId,
            BigDecimal debitAmount,
            BigDecimal creditAmount,
            String currency,
            String lineMemo,
            // --- cost-centre (ADR-0025 D-4) — optional; null = untagged ---
            Long costCentreValueId,
            Long departmentValueId,
            Long dimension3ValueId,
            Long dimension4ValueId,
            // --- projects (ADR-0033 D-1) — dedicated scalar tags; null = untagged ---
            Long projectId,
            Long projectTaskId,
            String projectCostType
    ) {
        /**
         * 5-arg convenience constructor — keeps every existing poster's call site unchanged
         * (NFR-CC-01). Defaults all dimension and project ids to null (untagged).
         */
        public LineDraft(Long accountId, BigDecimal debitAmount, BigDecimal creditAmount,
                         String currency, String lineMemo) {
            this(accountId, debitAmount, creditAmount, currency, lineMemo,
                 null, null, null, null, null, null, null);
        }

        /**
         * 9-arg backward-compatible constructor for callers that set dimension ids but not
         * project ids (ADR-0025 D-4 callers — zero regression, NFR-CC-01).
         */
        public LineDraft(Long accountId, BigDecimal debitAmount, BigDecimal creditAmount,
                         String currency, String lineMemo,
                         Long costCentreValueId, Long departmentValueId,
                         Long dimension3ValueId, Long dimension4ValueId) {
            this(accountId, debitAmount, creditAmount, currency, lineMemo,
                 costCentreValueId, departmentValueId, dimension3ValueId, dimension4ValueId,
                 null, null, null);
        }
    }
}
