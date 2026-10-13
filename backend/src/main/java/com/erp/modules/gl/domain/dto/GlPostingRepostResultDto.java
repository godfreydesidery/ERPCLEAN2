package com.erp.modules.gl.domain.dto;

import java.time.LocalDate;

/**
 * Result of re-posting a GL posting exception (ACC-02).
 *
 * @param exceptionUid    the exception that is now resolved
 * @param outcome         {@code REPOSTED} (a journal was posted now) or {@code ALREADY_POSTED}
 *                        (a journal for the source had appeared since the failure — nothing new
 *                        was posted, the exception was only closed)
 * @param journalEntryUid the journal that carries the posting
 * @param batchNumber     that journal's batch number
 * @param postingDate     that journal's posting date
 */
public record GlPostingRepostResultDto(
        String exceptionUid,
        String outcome,
        String journalEntryUid,
        String batchNumber,
        LocalDate postingDate
) {}
