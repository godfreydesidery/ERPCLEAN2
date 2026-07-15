package com.erp.platform.bulk;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One parsed data row from an uploaded spreadsheet: the 1-based spreadsheet row number (for error
 * messages) and the cell values keyed by column header. Values are trimmed; a blank or missing cell
 * reads back as an empty string.
 *
 * <p>Headers are matched ignoring case and surrounding space — a sheet that has been through Excel,
 * a re-save or a hand edit should not lose a column to "Product code" vs "Product Code". This is the
 * same normalisation {@link BulkImportService} uses to check an upload belongs to the chosen entity,
 * so a file it accepts is a file whose columns the handlers can actually read.
 */
public final class ImportRow {

    private final int rowNumber;
    private final Map<String, String> cells;

    public ImportRow(int rowNumber, Map<String, String> cells) {
        this.rowNumber = rowNumber;
        this.cells = new LinkedHashMap<>();
        cells.forEach((header, value) -> this.cells.put(normalise(header), value));
    }

    public int rowNumber() {
        return rowNumber;
    }

    /** Trimmed cell value for {@code header}, or "" if blank/absent. */
    public String get(String header) {
        String v = cells.get(normalise(header));
        return v == null ? "" : v.trim();
    }

    private static String normalise(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT);
    }

    /** True if the cell has a non-blank value. */
    public boolean has(String header) {
        return !get(header).isEmpty();
    }
}
