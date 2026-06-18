package com.erp.platform.common.money;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Thin immutable value type for an ISO-4217 alpha-3 currency code (ADR-0039 D-1).
 *
 * <p>Validates format (exactly 3 ASCII letters) and normalises (trim + upper-case) on construction.
 * Does <em>not</em> validate existence/active status in the {@code currencies} master — that is a
 * service-layer concern (D-5).
 *
 * <p>Jackson: serialises to/from the plain string {@code "TZS"} via {@link JsonValue}/
 * {@link JsonCreator} — the JSON wire shape is unchanged.
 *
 * <p>JPA persistence: {@link CurrencyCodeConverter} (auto-applied) maps to/from the existing
 * {@code CHAR(3)} column — no schema change required.
 *
 * <p>Naming rule: fields must be typed {@code CurrencyCode}, never {@code Currency}
 * ({@code Currency} is the JPA master entity in the {@code fx} module).
 */
public record CurrencyCode(@JsonValue String value) {

    private static final java.util.regex.Pattern ALPHA3 =
            java.util.regex.Pattern.compile("^[A-Z]{3}$");

    /**
     * Compact canonical constructor — normalises and validates.
     *
     * @param value the raw code; trimmed + upper-cased, then validated against {@code ^[A-Z]{3}$}
     * @throws IllegalArgumentException if null, blank, or not exactly 3 ASCII letters after
     *                                  normalisation
     */
    public CurrencyCode {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CurrencyCode must not be null or blank");
        }
        value = value.strip().toUpperCase();
        if (!ALPHA3.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "CurrencyCode must be exactly 3 ASCII letters (ISO-4217 alpha-3), got: '" + value + "'");
        }
    }

    /**
     * Factory method — normalises (trim + upper-case) and validates.
     * Use this as the single entry-point for constructing a {@code CurrencyCode} from a raw string.
     *
     * @param raw the raw currency code string
     * @return a validated, normalised {@code CurrencyCode}
     * @throws IllegalArgumentException if null/blank/invalid
     */
    @JsonCreator
    public static CurrencyCode of(String raw) {
        return new CurrencyCode(raw);
    }

    /**
     * Null-safe factory: returns {@code null} for a null/blank input, otherwise a validated
     * {@code CurrencyCode}. Use for nullable currency columns (the DB enforces NOT NULL where required).
     */
    public static CurrencyCode ofNullable(String raw) {
        return (raw == null || raw.isBlank()) ? null : new CurrencyCode(raw);
    }

    /** Null-safe accessor: the plain code of {@code cc}, or {@code null} if {@code cc} is null. */
    public static String value(CurrencyCode cc) {
        return cc == null ? null : cc.value();
    }

    /**
     * Returns the normalised 3-letter code, e.g. {@code "TZS"}.
     * Annotated {@link JsonValue} so Jackson serialises this object as the plain string.
     */
    @Override
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
