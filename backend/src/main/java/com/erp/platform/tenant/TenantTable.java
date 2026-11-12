package com.erp.platform.tenant;

import java.util.List;

/**
 * One table that holds a tenant's data, with the SQL predicate that selects exactly that tenant's
 * rows.
 *
 * @param name            table name (taken from the catalogue, never from request input)
 * @param kind            how the table is tied to the tenant
 * @param columns         every column, in ordinal order
 * @param exportColumns   columns that leave in an export (columns minus {@code redactedColumns})
 * @param redactedColumns columns holding credentials; never exported
 * @param primaryKey      primary-key columns (export ordering); may be empty
 * @param rowFilterSql    boolean SQL over this table's own columns selecting the tenant's rows.
 *                        The organisation id is inlined as a numeric literal (it is a database id,
 *                        not user input), so the fragment can be reused verbatim in subqueries.
 */
public record TenantTable(String name, Kind kind, List<String> columns, List<String> exportColumns,
                          List<String> redactedColumns, List<String> primaryKey,
                          String rowFilterSql) {

    /** How a table is tied to its tenant. */
    public enum Kind {
        /** The {@code organisations} row itself. */
        ROOT,
        /** Carries {@code organisation_id} and/or {@code company_id}. */
        DIRECT,
        /** Carries neither; reached by foreign key from a scoped table. */
        CHILD
    }
}
