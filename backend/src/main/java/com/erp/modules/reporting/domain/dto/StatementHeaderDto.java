package com.erp.modules.reporting.domain.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Common header attached to every statement DTO (ADR-0018 D-1).
 *
 * @param branchUid   the branch the statement is narrowed to, or null
 * @param branchLabel what the header prints for the branch: the branch name, "All branches", or
 *                    "Company-level entries (no branch)". Never null.
 */
public record StatementHeaderDto(
        Long        companyId,
        String      companyName,
        String      currency,
        String      periodLabel,
        String      comparativeLabel,
        LocalDate   fromDate,
        LocalDate   toDate,
        LocalDate   asAtDate,
        Instant     generatedAt,
        String      branchUid,
        String      branchLabel
) {

    public static final String ALL_BRANCHES = "All branches";

    public StatementHeaderDto {
        if (branchLabel == null || branchLabel.isBlank()) {
            branchLabel = ALL_BRANCHES;
        }
    }

    /** A company-wide header — the shape every statement used before branch statements. */
    public StatementHeaderDto(Long companyId, String companyName, String currency,
                              String periodLabel, String comparativeLabel,
                              LocalDate fromDate, LocalDate toDate, LocalDate asAtDate,
                              Instant generatedAt) {
        this(companyId, companyName, currency, periodLabel, comparativeLabel,
                fromDate, toDate, asAtDate, generatedAt, null, ALL_BRANCHES);
    }
}
