package com.erp.platform.bulk;

import java.util.List;

/**
 * One column in a bulk-import template (mass data operations for master data).
 *
 * <p>{@code header} is the human-facing column title written to the spreadsheet AND the key an
 * uploaded cell is read back by. A {@code required} column is asterisked in the generated template
 * and rejected when blank. {@code allowedValues}, when non-null, drives an Excel dropdown
 * (data-validation) on the column and documents the closed value set on the Instructions sheet.
 * {@code help} is the per-column note on the Instructions sheet.
 */
public record ColumnSpec(String header, boolean required, String help, List<String> allowedValues) {

    /** A free-text column. */
    public static ColumnSpec of(String header, boolean required, String help) {
        return new ColumnSpec(header, required, help, null);
    }

    /** A column constrained to a closed set of values (rendered as a dropdown). */
    public static ColumnSpec choice(String header, boolean required, String help, List<String> values) {
        return new ColumnSpec(header, required, help, values);
    }
}
