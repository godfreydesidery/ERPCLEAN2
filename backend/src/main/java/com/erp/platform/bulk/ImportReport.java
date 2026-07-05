package com.erp.platform.bulk;

import java.util.List;

/**
 * Aggregate outcome of a bulk run — counts plus the per-row detail the UI renders as a preview
 * (VALIDATE) or a result table (COMMIT).
 */
public record ImportReport(
        String entityKey,
        ImportMode mode,
        int total,
        int created,
        int updated,
        int skipped,
        int errors,
        List<RowOutcome> rows) {

    public static ImportReport from(String entityKey, ImportMode mode, List<RowOutcome> rows) {
        int created = 0;
        int updated = 0;
        int skipped = 0;
        int errors = 0;
        for (RowOutcome r : rows) {
            switch (r.action()) {
                case CREATE -> created++;
                case UPDATE -> updated++;
                case SKIP -> skipped++;
                case ERROR -> errors++;
            }
        }
        return new ImportReport(entityKey, mode, rows.size(), created, updated, skipped, errors, rows);
    }
}
