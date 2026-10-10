package com.erp.modules.gl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.gl.domain.dto.FiscalYearDto;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** ACC-01: the rollover job's per-company loop — scope, time zone, isolation of failures. */
class FiscalYearRolloverJobTest {

    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final FiscalCalendarService calendar = mock(FiscalCalendarService.class);
    private final FiscalYearRolloverJob job = new FiscalYearRolloverJob(companies, calendar, true);

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void usesEachCompanysOwnTimeZone_toDecideWhatTodayIs() {
        // 22:00 UTC on 31 Dec is already 1 Jan in Dar es Salaam (UTC+3).
        Company dar = company(1L, MasterStatus.ACTIVE, "Africa/Dar_es_Salaam");
        Company utc = company(2L, MasterStatus.ACTIVE, "UTC");
        when(companies.findAll()).thenReturn(List.of(dar, utc));

        job.rollOverAllCompanies(Instant.parse("2026-12-31T22:00:00Z"));

        verify(calendar).ensureCurrentAndNextYear(1L, LocalDate.of(2027, 1, 1));
        verify(calendar).ensureCurrentAndNextYear(2L, LocalDate.of(2026, 12, 31));
    }

    @Test
    void skipsCompaniesThatAreNotActive() {
        Company inactive = company(1L, MasterStatus.INACTIVE, "UTC");
        Company archived = company(2L, MasterStatus.ARCHIVED, "UTC");
        when(companies.findAll()).thenReturn(List.of(inactive, archived));

        job.rollOverAllCompanies(Instant.parse("2026-10-10T00:00:00Z"));

        verify(calendar, never()).ensureCurrentAndNextYear(any(), any());
    }

    @Test
    void oneCompanyFailing_doesNotStopTheOthers_andCountsWhatWasOpened() {
        Company one = company(1L, MasterStatus.ACTIVE, "UTC");
        Company two = company(2L, MasterStatus.ACTIVE, "UTC");
        when(companies.findAll()).thenReturn(List.of(one, two));
        when(calendar.ensureCurrentAndNextYear(eq(1L), any()))
                .thenThrow(new IllegalStateException("boom"));
        when(calendar.ensureCurrentAndNextYear(eq(2L), any()))
                .thenReturn(List.of(mock(FiscalYearDto.class)));

        int opened = job.rollOverAllCompanies(Instant.parse("2026-10-10T00:00:00Z"));

        assertThat(opened).isEqualTo(1);
    }

    @Test
    void runsUnderASystemPrincipalForTheCompany_andRestoresTheCallersContext() {
        RequestContext.Principal caller = new RequestContext.Principal(9L, "u", false, 3L, 4L, null);
        RequestContext.set(caller);
        Company one = company(1L, MasterStatus.ACTIVE, "UTC");
        when(companies.findAll()).thenReturn(List.of(one));
        when(calendar.ensureCurrentAndNextYear(eq(1L), any())).thenAnswer(inv -> {
            RequestContext.Principal p = RequestContext.get();
            assertThat(p.system()).isTrue();
            assertThat(p.companyId()).isEqualTo(1L);
            return List.of();
        });

        job.rollOverAllCompanies(Instant.parse("2026-10-10T00:00:00Z"));

        assertThat(RequestContext.get()).isSameAs(caller);
    }

    @Test
    void badTimeZone_fallsBackToDarEsSalaam_ratherThanSkippingTheCompany() {
        Company odd = company(1L, MasterStatus.ACTIVE, "Not/AZone");
        when(companies.findAll()).thenReturn(List.of(odd));

        // 22:00 UTC on 31 Dec is 01:00 EAT on 1 Jan: the house-zone fallback (owner ruling
        // 2026-10-10) puts it in the new year, where the UTC fallback used to keep it in the old.
        job.rollOverAllCompanies(Instant.parse("2026-12-31T22:00:00Z"));

        verify(calendar).ensureCurrentAndNextYear(1L, LocalDate.of(2027, 1, 1));
    }

    @Test
    void disabled_doesNothing() {
        FiscalYearRolloverJob off = new FiscalYearRolloverJob(companies, calendar, false);
        off.onApplicationReady();
        off.daily();
        verify(companies, never()).findAll();
    }

    private static Company company(Long id, MasterStatus status, String tz) {
        Company c = mock(Company.class);
        when(c.getId()).thenReturn(id);
        when(c.getStatus()).thenReturn(status);
        when(c.getTimeZone()).thenReturn(tz);
        return c;
    }
}
