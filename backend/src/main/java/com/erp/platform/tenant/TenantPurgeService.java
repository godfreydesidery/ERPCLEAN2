package com.erp.platform.tenant;

import java.util.List;
import java.util.function.Consumer;

/**
 * Removes every row of one organisation (MULTITENANCY-PLAN §14.2 R-8). This class decides HOW; the
 * caller decides WHETHER (suspension, grace period, prior export, confirmation) and has already
 * enforced it. The one thing it re-checks itself is that the organisation is still suspended, under
 * a row lock, because that is the only check a concurrent "resume" could race.
 *
 * <p><b>Audit-trail decision.</b> The tenant's own {@code audit_logs} rows (by organisation,
 * company, or acting user) are tenant data and ARE deleted: they record the departed customer's
 * staff, logins and IP addresses, keeping them after departure is a liability with no owner, and
 * the actor foreign key to {@code app_users} would block the user delete anyway. What survives is
 * the platform operator's lifecycle trail about the tenant ({@code target_type='organisations'}
 * rows: create, suspend, export) and the final {@code ORG.PURGE} row with per-table counts. This is
 * the single deliberate exception to "audit rows are never deleted through the application".
 */
public interface TenantPurgeService {

    /** Counts what a purge would delete, in deletion order. Deletes nothing. */
    PurgeReport dryRun(TenantRef organisation);

    /**
     * Deletes everything in ONE transaction (lock timeout 10 s, bounded statement time): either the
     * whole tenant goes or nothing does. {@code auditInTransaction} runs after the last delete and
     * inside the same transaction, so the {@code ORG.PURGE} row commits with the purge or not at
     * all.
     *
     * @throws com.erp.platform.common.api.ConflictException when the organisation is not suspended,
     *         the plan has a blocker (cycle, trigger), or the database refuses a delete; the
     *         technical cause is logged and nothing is deleted
     */
    PurgeReport purge(TenantRef organisation, Consumer<PurgeReport> auditInTransaction);

    /**
     * @param dryRun   whether rows were only counted
     * @param tables   rows per table, in deletion order (children first, organisation last)
     * @param blockers reasons a real purge would refuse (empty when none)
     */
    record PurgeReport(boolean dryRun, List<TableCount> tables, long totalRows,
                       List<String> blockers) {
    }

    record TableCount(String table, long rows) {
    }
}
