package com.erp.platform.bulk;

import java.util.List;

/**
 * One column in a bulk-import template (mass data operations for master data).
 *
 * <p>{@code header} is the human-facing column title written to the spreadsheet AND the key an
 * uploaded cell is read back by. A {@code required} column is asterisked in the generated template
 * and rejected when blank. {@code allowedValues}, when non-null, drives an Excel dropdown
 * (data-validation) on the column and documents the closed value set on the Instructions sheet.
 * {@code help} is the per-column note on the Instructions sheet. A {@code reference} column is
 * READ-ONLY context (e.g. the product name next to a price) — shown in the sheet to orient the
 * editor but never imported; the framework and handlers ignore its value on re-upload. A
 * {@code numeric} column is written as a real numeric Excel cell (not text) so figures allow
 * arithmetic/formulas; it still reads back as its (trimmed, comma-stripped) text on re-upload.
 */
public record ColumnSpec(String header, boolean required, String help, List<String> allowedValues,
                         boolean reference, boolean numeric) {

    /** A free-text column. */
    public static ColumnSpec of(String header, boolean required, String help) {
        return new ColumnSpec(header, required, help, null, false, false);
    }

    /** A column constrained to a closed set of values (rendered as a dropdown). */
    public static ColumnSpec choice(String header, boolean required, String help, List<String> values) {
        return new ColumnSpec(header, required, help, values, false, false);
    }

    /**
     * A NUMBER column: written as a numeric Excel cell (with a thousands-separator format) so the
     * downloaded sheet supports arithmetic, SUM and formulas instead of "number stored as text".
     * Values still parse back normally on re-upload.
     */
    public static ColumnSpec number(String header, boolean required, String help) {
        return new ColumnSpec(header, required, help, null, false, true);
    }

    /**
     * A READ-ONLY reference column: pre-filled on export to give the editor context (e.g. the
     * product name and current cost beside the price) but NEVER imported — its value is ignored on
     * re-upload. Never required, never a dropdown; styled distinctly in the template.
     */
    public static ColumnSpec reference(String header, String help) {
        return new ColumnSpec(header, false, help, null, true, false);
    }

    /** A READ-ONLY reference column that holds a number (styled distinctly AND written numeric). */
    public static ColumnSpec numberReference(String header, String help) {
        return new ColumnSpec(header, false, help, null, true, true);
    }
}
