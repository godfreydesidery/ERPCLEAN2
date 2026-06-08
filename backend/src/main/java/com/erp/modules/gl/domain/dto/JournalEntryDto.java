package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.JournalSourceType;
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
        Instant postedAt,
        List<JournalLineDto> lines
) {}
