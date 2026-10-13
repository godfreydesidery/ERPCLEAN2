package com.erp.platform.common.time;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * The "Generated: ..." stamp every exported report and printed document carries.
 *
 * <p>Report DTOs keep {@code generatedAt} as an ISO-8601 instant — that is API surface and the web
 * formats it client-side. The export renderers print it on paper, where a raw
 * {@code 2026-10-10T03:21:19.624913Z} is three hours behind the shop's clock and unreadable (ADM-27,
 * LBO-22/23). This turns an ISO instant into {@code dd-MMM-yyyy HH:mm} in the zone of the company
 * the request acts in (owner ruling 2026-10-10); anything that is not an ISO instant — a stamp a
 * flattener already formatted — passes through unchanged.
 */
public final class PrintedStamp {

    private PrintedStamp() {
    }

    /** {@code raw} formatted for print; empty for null. */
    public static String of(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        Instant at;
        try {
            at = Instant.parse(s);
        } catch (DateTimeParseException notInstant) {
            try {
                at = OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException notOffset) {
                return raw;
            }
        }
        return BusinessZone.formatDateTime(at, CompanyCalendar.zoneOfCurrentRequest());
    }
}
