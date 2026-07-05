package com.erp.platform.bulk;

import java.util.Map;

/**
 * One parsed data row from an uploaded spreadsheet: the 1-based spreadsheet row number (for error
 * messages) and the cell values keyed by column header. Values are trimmed; a blank or missing cell
 * reads back as an empty string.
 */
public final class ImportRow {

    private final int rowNumber;
    private final Map<String, String> cells;

    public ImportRow(int rowNumber, Map<String, String> cells) {
        this.rowNumber = rowNumber;
        this.cells = cells;
    }

    public int rowNumber() {
        return rowNumber;
    }

    /** Trimmed cell value for {@code header}, or "" if blank/absent. */
    public String get(String header) {
        String v = cells.get(header);
        return v == null ? "" : v.trim();
    }

    /** True if the cell has a non-blank value. */
    public boolean has(String header) {
        return !get(header).isEmpty();
    }
}
