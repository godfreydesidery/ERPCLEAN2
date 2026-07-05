package com.erp.platform.bulk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-run scratchpad handed to every {@link BulkImportHandler#process} call of a single upload, so a
 * handler can see across the rows of THIS file — e.g. to reject a barcode claimed twice in the same
 * sheet before it ever reaches the database. Created fresh per run by {@link BulkImportService};
 * never shared between requests (so it needs no synchronisation).
 */
public final class ImportContext {

    private final Map<String, Set<String>> claims = new HashMap<>();

    /**
     * A run-scoped mutable set for a {@code namespace} (e.g. {@code "barcode"}). Use
     * {@code claimSet(ns).add(value)} — a {@code false} return means the value already appeared
     * earlier in this file.
     */
    public Set<String> claimSet(String namespace) {
        return claims.computeIfAbsent(namespace, k -> new HashSet<>());
    }
}
