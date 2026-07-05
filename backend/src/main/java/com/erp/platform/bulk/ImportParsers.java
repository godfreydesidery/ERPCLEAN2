package com.erp.platform.bulk;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Small, user-safe parsers for spreadsheet cell strings. Each throws {@link IllegalArgumentException}
 * with a friendly message (no internal detail) that the framework surfaces on the offending row.
 */
public final class ImportParsers {

    private ImportParsers() {
    }

    public static String requireText(ImportRow row, String header) {
        String v = row.get(header);
        if (v.isEmpty()) {
            throw new IllegalArgumentException("'" + header + "' is required.");
        }
        return v;
    }

    /** Optional text: the trimmed value, or {@code null} when the cell is blank. */
    public static String text(ImportRow row, String header) {
        String v = row.get(header);
        return v.isEmpty() ? null : v;
    }

    /**
     * Optional 2-letter ISO code (e.g. country). Null when blank; upper-cased; rejected with a
     * friendly message when not exactly two letters — so a full country name gives a clear error
     * instead of a cryptic "value too long" DB constraint.
     */
    public static String isoCode2(ImportRow row, String header) {
        String v = text(row, header);
        if (v == null) {
            return null;
        }
        v = v.toUpperCase();
        if (v.length() != 2) {
            throw new IllegalArgumentException(
                    "'" + header + "' must be a 2-letter ISO code, e.g. TZ (got '" + v + "').");
        }
        return v;
    }

    /** Yes/No (also y/n, true/false, 1/0). Returns {@code dflt} when the cell is blank. */
    public static Boolean parseBool(ImportRow row, String header, Boolean dflt) {
        String v = row.get(header);
        if (v.isEmpty()) {
            return dflt;
        }
        return switch (v.toLowerCase()) {
            case "yes", "y", "true", "1" -> Boolean.TRUE;
            case "no", "n", "false", "0" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException(
                    "'" + header + "' must be Yes or No (got '" + v + "').");
        };
    }

    public static boolean parseBoolRequired(ImportRow row, String header) {
        Boolean b = parseBool(row, header, null);
        if (b == null) {
            throw new IllegalArgumentException("'" + header + "' is required (Yes or No).");
        }
        return b;
    }

    /** Enum by name, case/whitespace/hyphen-insensitive. Returns {@code dflt} when the cell is blank. */
    public static <E extends Enum<E>> E parseEnum(Class<E> type, ImportRow row, String header, E dflt) {
        String v = row.get(header);
        if (v.isEmpty()) {
            return dflt;
        }
        String norm = v.toUpperCase().trim().replace(' ', '_').replace('-', '_');
        try {
            return Enum.valueOf(type, norm);
        } catch (IllegalArgumentException e) {
            String allowed = Arrays.stream(type.getEnumConstants())
                    .map(Enum::name)
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    "'" + header + "' must be one of: " + allowed + " (got '" + v + "').");
        }
    }

    public static <E extends Enum<E>> E parseEnumRequired(Class<E> type, ImportRow row, String header) {
        E e = parseEnum(type, row, header, null);
        if (e == null) {
            throw new IllegalArgumentException("'" + header + "' is required.");
        }
        return e;
    }

    /** Decimal (thousands separators tolerated). Null when blank. */
    public static BigDecimal parseDecimal(ImportRow row, String header) {
        String v = row.get(header);
        if (v.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(v.replace(",", "").replace(" ", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + header + "' must be a number (got '" + v + "').");
        }
    }

    /** Whole number. Null when blank. */
    public static Integer parseInt(ImportRow row, String header) {
        String v = row.get(header);
        if (v.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(v.replace(",", "").replace(" ", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "'" + header + "' must be a whole number (got '" + v + "').");
        }
    }
}
