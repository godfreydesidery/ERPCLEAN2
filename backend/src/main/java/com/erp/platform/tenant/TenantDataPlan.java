package com.erp.platform.tenant;

import java.util.List;

/**
 * Everything the export and purge engines need to know about where one organisation's data lives.
 *
 * @param organisationId    the tenant
 * @param tables            tenant tables in DELETION order: a table appears after every table that
 *                          holds a foreign key to it, so deleting top-to-bottom never orphans a row
 * @param globalTables      tables deliberately excluded (shared reference data / infrastructure)
 * @param cycleBreaks       foreign keys to null before delete because the graph has a cycle no
 *                          ordering can resolve; empty when the graph is a DAG
 * @param triggersSeen      every user trigger / rewrite rule on a tenant table, for the report
 * @param deletionBlockers  reasons the purge must refuse (an unresolvable cycle, a trigger or rule
 *                          that fires on DELETE, ...). Empty means the plan is safe to execute. Purge
 *                          never disables triggers to get past one; export ignores this list.
 */
public record TenantDataPlan(long organisationId, List<TenantTable> tables,
                             List<String> globalTables, List<CycleBreak> cycleBreaks,
                             List<String> triggersSeen, List<String> deletionBlockers) {

    /** A nullable foreign key to clear before delete so a mutual reference can be resolved. */
    public record CycleBreak(String table, List<String> columns) {
    }
}
