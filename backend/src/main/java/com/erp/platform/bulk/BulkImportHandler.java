package com.erp.platform.bulk;

import java.util.List;
import java.util.Map;

/**
 * Per-entity plug-in for the bulk-import framework. A handler lives in its own module and maps a
 * spreadsheet row onto that module's existing create/update service call — so every imported record
 * goes through the exact same validation, tenant scoping, outbox events and audit as a hand-entered
 * one. Nothing here bypasses the module service.
 *
 * <p>Discovered by Spring: implement, annotate {@code @Component}/{@code @Service}, and the
 * {@link BulkImportService} picks it up automatically.
 */
public interface BulkImportHandler {

    /** Stable url key for this entity, e.g. {@code "products"}. Unique across handlers. */
    String key();

    /** Human label shown in the picker and template title, e.g. {@code "Products"}. */
    String displayName();

    /**
     * Permission code required to import this entity (e.g. {@code PRODUCT.IMPORT}). The endpoint is
     * gated on it per-entity, so a role can be granted bulk import of one master and not another.
     */
    String permissionCode();

    /**
     * The template columns for {@code companyId} (the active company). Company-scoped so a handler
     * may inject live look-ups (e.g. existing price-list codes) as dropdowns.
     */
    List<ColumnSpec> columns(Long companyId);

    /**
     * Process one row for {@code companyId}. In {@link ImportMode#VALIDATE} resolve/validate but
     * persist nothing; in {@link ImportMode#COMMIT} perform the create/update. {@code ctx} is the
     * per-run scratchpad (shared across the rows of this one file) for cross-row checks such as
     * intra-sheet duplicate detection. Return a {@link RowOutcome}; throwing a {@link RuntimeException}
     * is also fine — the framework records it as an error row with the exception's (user-safe) message.
     */
    RowOutcome process(Long companyId, ImportRow row, ImportMode mode, ImportContext ctx);

    /**
     * Current rows for the "download → edit → re-upload" round-trip: the entity's existing records,
     * keyed by the same column headers as {@link #columns}, so an export fills the template and a
     * re-upload updates. {@code params} carries optional query parameters (e.g. a price-list code).
     * Default: no rows (export yields a blank template). Override to support export.
     */
    default List<java.util.LinkedHashMap<String, String>> exportRows(Long companyId, Map<String, String> params) {
        return List.of();
    }
}
