package com.erp.platform.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The one place a module asks "what day is it / what day was this, for THIS company?".
 *
 * <p>Owner ruling 2026-10-10 ("save UTC in the db but display in timezone"): instants are stored in
 * UTC and never change; every business date derived from one — GL posting date, VAT window, report
 * day/month buckets, stock-movement GL date, POS session date, a document's "today" — is computed
 * in the company's zone ({@code companies.time_zone}, falling back to {@link BusinessZone#DEFAULT}
 * when blank or invalid).
 *
 * <p>Lives in {@code platform.common} and reads the single column with plain JDBC so no module
 * imports the IAM {@code Company} entity (ModuleBoundaryTest). The zone is cached for a minute per
 * company: posting handlers and list endpoints call it per row/event, and a company's zone is
 * effectively static.
 */
@Component
public class CompanyCalendar {

    private static final long CACHE_TTL_MILLIS = 60_000L;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId fixedZone;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public CompanyCalendar(JdbcTemplate jdbc, Clock clock) {
        this.jdbc      = jdbc;
        this.clock     = clock;
        this.fixedZone = null;
    }

    private CompanyCalendar(ZoneId fixedZone, Clock clock) {
        this.jdbc      = null;
        this.clock     = clock;
        this.fixedZone = fixedZone;
    }

    /**
     * A calendar that answers {@code zone} for every company without touching the database — for
     * unit tests and for callers that already hold the zone.
     */
    public static CompanyCalendar fixed(ZoneId zone, Clock clock) {
        return new CompanyCalendar(zone, clock);
    }

    /** The company's business zone; {@link BusinessZone#DEFAULT} when unknown, blank or invalid. */
    public ZoneId zoneOf(Long companyId) {
        if (fixedZone != null) {
            return fixedZone;
        }
        if (companyId == null || jdbc == null) {
            return BusinessZone.DEFAULT;
        }
        long now = System.currentTimeMillis();
        Cached hit = cache.get(companyId);
        if (hit != null && hit.expiresAt() > now) {
            return hit.zone();
        }
        List<String> rows = jdbc.queryForList(
                "SELECT time_zone FROM companies WHERE id = ?", String.class, companyId);
        ZoneId zone = BusinessZone.parse(rows.isEmpty() ? null : rows.get(0));
        cache.put(companyId, new Cached(zone, now + CACHE_TTL_MILLIS));
        return zone;
    }

    /** Today's business date for the company. */
    public LocalDate today(Long companyId) {
        return LocalDate.now(clock.withZone(zoneOf(companyId)));
    }

    /** The current moment in the company's zone (for "Printed On" footprints). */
    public ZonedDateTime now(Long companyId) {
        return ZonedDateTime.now(clock.withZone(zoneOf(companyId)));
    }

    /**
     * The business date {@code at} falls on for the company; today's business date when {@code at}
     * is null (the "posting date defaults to today" fallback every poster used).
     */
    public LocalDate dateOf(Long companyId, Instant at) {
        return at == null ? today(companyId) : BusinessZone.dateOf(at, zoneOf(companyId));
    }

    /** Inclusive lower bound of {@code date} in the company's zone. */
    public Instant startOfDay(Long companyId, LocalDate date) {
        return BusinessZone.startOfDay(date, zoneOf(companyId));
    }

    /** Exclusive upper bound of {@code date} (start of the next day) in the company's zone. */
    public Instant endOfDayExclusive(Long companyId, LocalDate date) {
        return BusinessZone.endOfDayExclusive(date, zoneOf(companyId));
    }

    private record Cached(ZoneId zone, long expiresAt) {}
}
