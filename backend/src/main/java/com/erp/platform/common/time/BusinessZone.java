package com.erp.platform.common.time;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Pure helpers for turning a stored UTC instant into a business date and back.
 *
 * <p>Owner ruling 2026-10-10: <em>save UTC in the database, display in the time zone</em>. Every
 * instant stays UTC at rest; a business date that is derived from an instant (GL posting date, VAT
 * period window, day/month report buckets, POS session date, a "today" default) is computed in the
 * COMPANY's zone — never {@code ZoneOffset.UTC} or the JVM default. A sale rung up at 00:30 EAT on
 * 1 November is 21:30 UTC on 31 October, and it belongs to November.
 *
 * <p>The zone is read from {@code companies.time_zone}; see {@link CompanyCalendar}. When that is
 * blank or not a valid zone id the fallback is {@link #DEFAULT} (Africa/Dar_es_Salaam), which is
 * also the column's default.
 */
public final class BusinessZone {

    /** The fallback zone id when a company's {@code time_zone} is blank or invalid. */
    public static final String DEFAULT_ID = "Africa/Dar_es_Salaam";

    /** The fallback zone when a company's {@code time_zone} is blank or invalid. */
    public static final ZoneId DEFAULT = ZoneId.of(DEFAULT_ID);

    /** Printed date: {@code 10-Oct-2026}. */
    public static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    /** Printed date and time: {@code 10-Oct-2026 06:21}. */
    public static final DateTimeFormatter DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH);

    private BusinessZone() {
    }

    /** The zone for a stored zone id; blank or invalid falls back to {@link #DEFAULT}. */
    public static ZoneId parse(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return DEFAULT;
        }
        try {
            return ZoneId.of(timeZone.trim());
        } catch (DateTimeException e) {
            return DEFAULT;
        }
    }

    /** The calendar date {@code at} falls on in {@code zone}; null when {@code at} is null. */
    public static LocalDate dateOf(Instant at, ZoneId zone) {
        return at == null ? null : at.atZone(zone).toLocalDate();
    }

    /** The instant {@code date} starts at in {@code zone} (inclusive lower bound of that day). */
    public static Instant startOfDay(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant();
    }

    /** The instant the day AFTER {@code date} starts at in {@code zone} (exclusive upper bound). */
    public static Instant endOfDayExclusive(LocalDate date, ZoneId zone) {
        return date.plusDays(1).atStartOfDay(zone).toInstant();
    }

    /** {@code dd-MMM-yyyy} of {@code at} in {@code zone}; empty string when {@code at} is null. */
    public static String formatDate(Instant at, ZoneId zone) {
        return at == null ? "" : DATE_FMT.format(at.atZone(zone));
    }

    /** {@code dd-MMM-yyyy HH:mm} of {@code at} in {@code zone}; empty string when null. */
    public static String formatDateTime(Instant at, ZoneId zone) {
        return at == null ? "" : DATE_TIME_FMT.format(at.atZone(zone));
    }

    /** {@code dd-MMM-yyyy HH:mm} of a zoned moment; empty string when null. */
    public static String formatDateTime(ZonedDateTime at) {
        return at == null ? "" : DATE_TIME_FMT.format(at);
    }
}
