package com.erp.modules.parties.service;

import com.erp.platform.common.money.CurrencyCode;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Field-format guards for the party master write paths.
 *
 * <p><b>Why this exists (UAT wave 1).</b> Several party columns are short, coded columns —
 * {@code country VARCHAR(2)}, {@code default_currency VARCHAR(3)} — and the API happily accepted a
 * human-readable value such as {@code "Tanzania"}. Nothing rejected it until Postgres did, at which
 * point {@code GlobalExceptionHandler} turned SQLSTATE 22001 into "The supplied value violates a
 * data constraint. Check field formats, lengths, and numeric ranges." — a 400 that names no field
 * and suggests no correction. A procurement officer could not register a supplier at all; the only
 * way past it was reading the source.
 *
 * <p>Every method here throws {@link IllegalArgumentException} (mapped to 400) with a message that
 * names the field, states the expected form with a worked example, and echoes what was supplied so
 * the user can see which box to fix. No column name, constraint name, SQLSTATE or exception text
 * ever reaches the user. The bulk-import path already did this via {@code ImportParsers.isoCode2};
 * this is the same contract for the JSON API.
 *
 * <p>Deliberately validation-only — it never widens a column, which would need a migration.
 */
public final class PartyFieldValidator {

    /** ISO-3166 alpha-2: exactly two ASCII letters. Case is normalised, not rejected. */
    private static final Pattern ALPHA2 = Pattern.compile("^[A-Za-z]{2}$");

    /** Cap on how much of the user's own input is echoed back in an error message. */
    private static final int ECHO_LIMIT = 40;

    private PartyFieldValidator() {
    }

    /**
     * Normalises an optional ISO-3166 alpha-2 country code: {@code null}/blank passes through as
     * {@code null}, {@code "tz"} and {@code " TZ "} both become {@code "TZ"}, and anything that is
     * not two letters — most often the country NAME — is refused with an actionable message.
     */
    public static String isoCountryCode(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.isEmpty()) {
            return null;
        }
        if (!ALPHA2.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Country must be a two-letter ISO country code, not the country name. "
                            + "Enter TZ for Tanzania, KE for Kenya, UG for Uganda, and so on (got "
                            + echo(value) + ").");
        }
        return value.toUpperCase(Locale.ROOT);
    }

    /**
     * Normalises an optional ISO-4217 alpha-3 currency code, refusing anything else with a message
     * the user can act on. Wraps {@link CurrencyCode#ofNullable} so the caller never sees that
     * type's internal wording ("CurrencyCode must be exactly 3 ASCII letters..."), which names a
     * Java class rather than the field the user filled in.
     *
     * @param label how this field is called on screen, e.g. {@code "Default currency"}
     */
    public static CurrencyCode currencyCode(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CurrencyCode.ofNullable(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    label + " must be a three-letter ISO currency code. Enter TZS for Tanzanian "
                            + "Shilling, USD for US Dollar, KES for Kenyan Shilling, and so on (got "
                            + echo(raw.strip()) + ").");
        }
    }

    /**
     * Refuses a value longer than the column can hold, naming the field and the limit instead of
     * letting Postgres raise the anonymous truncation error. Returns the value unchanged so it can
     * be used inline at the assignment site.
     */
    public static String maxLength(String value, int max, String label) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(
                    label + " is too long: enter at most " + max + " characters (got "
                            + value.length() + ").");
        }
        return value;
    }

    /** Quote the user's own input for an error message, truncated so a paste can't bloat the error. */
    private static String echo(String value) {
        String shown = value.length() > ECHO_LIMIT
                ? value.substring(0, ECHO_LIMIT) + "..."
                : value;
        return "'" + shown + "'";
    }
}
