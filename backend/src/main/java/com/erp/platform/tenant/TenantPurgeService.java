package com.erp.platform.tenant;

import java.util.List;
import java.util.function.Consumer;

/**
 * Removes every row of one organisation (MULTITENANCY-PLAN §14.2 R-8). This class decides HOW; the
 * caller decides WHETHER (suspension, grace period, prior export, confirmation) and has already
 * enforced it. The one thing it re-checks itself is that the organisation is still suspended, under
 * a row lock, because that is the only check a concurrent "resume" could race.
 *
 * <p><b>Audit-trail decision (owner, 2026-11-07): audit rows are NEVER deleted.</b> A tenant that
 * has any audit history of its own (rows by organisation, company, or acting user) cannot be
 * purged at all; the purge refuses, inside its transaction, before anything is deleted, and the
 * delete loop never issues a {@code DELETE} against {@code audit_logs}. CLAUDE.md invariant 7
 * therefore holds with no exception. The consequence is accepted knowingly: only a tenant that was
 * never used (provisioned, never signed in to) can be removed; a used tenant is suspended and
 * exported instead. The platform operator's lifecycle rows about the tenant
 * ({@code target_type='organisations'}) are not the tenant's history and do not block it.
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
