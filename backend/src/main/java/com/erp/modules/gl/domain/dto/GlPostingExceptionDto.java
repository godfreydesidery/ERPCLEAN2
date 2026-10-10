package com.erp.modules.gl.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One automatic GL posting that failed and was swallowed so its business document could stand
 * (ACC-02), as listed on the GL "Posting exceptions" screen.
 *
 * @param uid             the exception's own id (a ULID; addresses the re-post action)
 * @param kind            which poster failed ({@code GlPostingFailureKind} name)
 * @param sourceType      journal source type the posting would have carried
 * @param sourceRef       source document uid
 * @param documentNumber  human-readable document number, when the poster knew it
 * @param postingDate     the posting date that was attempted
 * @param amount          posting total when known
 * @param reason          user-safe reason the posting failed
 * @param failedAt        when the failure was recorded
 * @param status          {@code OPEN} or {@code RESOLVED}
 * @param resolvedAt      when it was resolved (null while open)
 * @param resolvedBy      username that resolved it (null while open)
 * @param outcome         {@code REPOSTED} or {@code ALREADY_POSTED} (null while open)
 * @param journalEntryUid journal that now carries the posting (null while open)
 * @param batchNumber     that journal's batch number (null while open)
 */
public record GlPostingExceptionDto(
        String uid,
        String kind,
        String sourceType,
        String sourceRef,
        String documentNumber,
        LocalDate postingDate,
        BigDecimal amount,
        String reason,
        Instant failedAt,
        String status,
        Instant resolvedAt,
        String resolvedBy,
        String outcome,
        String journalEntryUid,
        String batchNumber
) {}
