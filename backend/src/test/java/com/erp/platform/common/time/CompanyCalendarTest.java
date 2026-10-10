package com.erp.platform.common.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Owner ruling 2026-10-10: "save UTC in the db but display in timezone". A business date derived
 * from a stored instant is the COMPANY's date — the case that matters is the bar sale at 00:30 EAT
 * on the 1st, which is 21:30 UTC on the last day of the previous month.
 */
class CompanyCalendarTest {

    /** 00:30 EAT on 1 November 2026 == 21:30 UTC on 31 October 2026. */
    private static final Instant HALF_PAST_MIDNIGHT_EAT_1_NOV = Instant.parse("2026-10-31T21:30:00Z");

    @Test
    void anAfterMidnightEatSale_fallsOnTheEatDate_notTheUtcDate() {
        CompanyCalendar cal = CompanyCalendar.fixed(BusinessZone.DEFAULT, Clock.systemUTC());

        assertThat(cal.dateOf(1L, HALF_PAST_MIDNIGHT_EAT_1_NOV)).isEqualTo(LocalDate.of(2026, 11, 1));
        // The old derivation, for contrast — it put the sale in October.
        assertThat(HALF_PAST_MIDNIGHT_EAT_1_NOV.atZone(ZoneOffset.UTC).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void novembersWindow_inEat_containsThe0030Sale_andOctobersDoesNot() {
        CompanyCalendar cal = CompanyCalendar.fixed(BusinessZone.DEFAULT, Clock.systemUTC());

        Instant novStart = cal.startOfDay(1L, LocalDate.of(2026, 11, 1));
        Instant novEnd   = cal.endOfDayExclusive(1L, LocalDate.of(2026, 11, 30));
        Instant octEnd   = cal.endOfDayExclusive(1L, LocalDate.of(2026, 10, 31));

        assertThat(novStart).isEqualTo(Instant.parse("2026-10-31T21:00:00Z"));
        assertThat(novEnd).isEqualTo(Instant.parse("2026-11-30T21:00:00Z"));
        assertThat(HALF_PAST_MIDNIGHT_EAT_1_NOV).isAfterOrEqualTo(novStart).isBefore(novEnd);
        assertThat(HALF_PAST_MIDNIGHT_EAT_1_NOV).isAfterOrEqualTo(octEnd);
    }

    @Test
    void today_isTheCompanysDate_evenWhenUtcIsStillYesterday() {
        Clock at2130Utc = Clock.fixed(HALF_PAST_MIDNIGHT_EAT_1_NOV, ZoneOffset.UTC);
        CompanyCalendar cal = CompanyCalendar.fixed(BusinessZone.DEFAULT, at2130Utc);

        assertThat(cal.today(1L)).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(cal.now(1L).getHour()).isZero();
        assertThat(cal.dateOf(1L, null)).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void zone_isReadFromTheCompanyRow_andCached() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(5L)))
                .thenReturn(List.of("Africa/Nairobi"));
        CompanyCalendar cal = new CompanyCalendar(jdbc, Clock.systemUTC());

        assertThat(cal.zoneOf(5L)).isEqualTo(ZoneId.of("Africa/Nairobi"));
        assertThat(cal.zoneOf(5L)).isEqualTo(ZoneId.of("Africa/Nairobi"));
        verify(jdbc, times(1)).queryForList(anyString(), eq(String.class), eq(5L));
    }

    @Test
    void blankInvalidOrMissingZone_fallsBackToDarEsSalaam() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(1L))).thenReturn(List.of(" "));
        when(jdbc.queryForList(anyString(), eq(String.class), eq(2L))).thenReturn(List.of("Mars/Olympus"));
        when(jdbc.queryForList(anyString(), eq(String.class), eq(3L))).thenReturn(List.of());
        CompanyCalendar cal = new CompanyCalendar(jdbc, Clock.systemUTC());

        assertThat(cal.zoneOf(1L)).isEqualTo(BusinessZone.DEFAULT);
        assertThat(cal.zoneOf(2L)).isEqualTo(BusinessZone.DEFAULT);
        assertThat(cal.zoneOf(3L)).isEqualTo(BusinessZone.DEFAULT);
        assertThat(cal.zoneOf(null)).isEqualTo(BusinessZone.DEFAULT);
        assertThat(BusinessZone.DEFAULT.getId()).isEqualTo("Africa/Dar_es_Salaam");
    }

    @Test
    void printedFormats_areDdMmmYyyy_inTheZone() {
        assertThat(BusinessZone.formatDate(HALF_PAST_MIDNIGHT_EAT_1_NOV, BusinessZone.DEFAULT))
                .isEqualTo("01-Nov-2026");
        assertThat(BusinessZone.formatDateTime(HALF_PAST_MIDNIGHT_EAT_1_NOV, BusinessZone.DEFAULT))
                .isEqualTo("01-Nov-2026 00:30");
        assertThat(BusinessZone.formatDate(null, BusinessZone.DEFAULT)).isEmpty();
    }
}
