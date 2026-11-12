package com.erp.modules.iam.domain.dto;

import java.util.List;

/**
 * Result of a purge or a purge dry run. Counts are {@code long} and so, like every Long on this
 * API, serialise as JSON strings.
 *
 * @param dryRun   true when nothing was deleted
 * @param eligible whether a real purge would be accepted right now (every departure rule holds and
 *                 the plan has no blocker); always true after a real purge
 * @param blockers friendly reasons a real purge would be refused; empty when eligible
 * @param tables   rows per table in deletion order (children first, the organisation last): the
 *                 rows that WOULD be deleted on a dry run, the rows that WERE deleted otherwise
 */
public record TenantPurgeReportDto(String organisationUid, String organisationName, boolean dryRun,
                                   boolean eligible, List<String> blockers, long totalRows,
                                   List<TableRowCountDto> tables) {

    public record TableRowCountDto(String table, long rows) {
    }
}
