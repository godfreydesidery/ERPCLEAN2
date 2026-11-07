package com.erp.platform.tenant;

/** Discovers, at runtime, which tables hold one organisation's data and in what order to delete. */
public interface TenantDataCatalog {

    /**
     * Builds the plan from the live schema. Cheap (a handful of catalogue queries) and never
     * cached, so a migration that adds a table is covered by the next call with no code change.
     *
     * Unorderable cycles and blocking triggers are reported in the plan, not thrown.
     */
    TenantDataPlan planFor(long organisationId);
}
