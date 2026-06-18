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
        List<JournalLineDto> lines
) {}
