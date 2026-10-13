package com.erp.modules.gl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.gl.domain.dto.OpenFiscalYearRequest;
import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.domain.entity.FiscalYear;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
import com.erp.modules.gl.repository.FiscalYearRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.ScopeGuard;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for the fiscal-calendar rules that need no database (ACC-09). */
class FiscalCalendarServiceImplTest {

    private static final long COMPANY = 5L;

    private final FiscalYearRepository years = mock(FiscalYearRepository.class);
    private final FiscalPeriodRepository periods = mock(FiscalPeriodRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final ScopeGuard scopeGuard = mock(ScopeGuard.class);
    private final AuditService audit = mock(AuditService.class);

    private final FiscalCalendarServiceImpl service =
            new FiscalCalendarServiceImpl(years, periods, companies, scopeGuard, audit);

    /** Everything the service saved, in order — the repositories are mocks. */
    private final List<FiscalYear> savedYears = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY);
        when(companies.findByUid("CO")).thenReturn(Optional.of(company));
        when(years.findByCompanyIdAndYearCode(anyLong(), anyString())).thenReturn(Optional.empty());
        when(years.save(any(FiscalYear.class))).thenAnswer(inv -> {
            FiscalYear y = inv.getArgument(0);
            savedYears.add(y);
            return y;
        });
        when(periods.save(any(FiscalPeriod.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------------------------------------------------------------------------------------------
    // ACC-09: overlapping fiscal years are refused
    // ---------------------------------------------------------------------------------------------

    @Test
    void openFiscalYear_overlappingAnExistingYear_isRefusedWithBothYearsNamed() {
        FiscalYear fy2027 = year("FY2027", LocalDate.of(2027, 1, 1));
        when(years.findOverlapping(eq(COMPANY), any(), any())).thenReturn(List.of(fy2027));

        assertThatThrownBy(() -> service.openFiscalYear(
                new OpenFiscalYearRequest("CO", "2027", 1, 2027)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("would overlap fiscal year FY2027")
                .hasMessageContaining("1 Jan 2027 to 31 Dec 2027");
        verify(years, never()).save(any());
    }

    @Test
    void openFiscalYear_julyToJuneOverACalendarYear_isRefused() {
        FiscalYear fy2026 = year("FY2026", LocalDate.of(2026, 1, 1));
        when(years.findOverlapping(COMPANY, LocalDate.of(2026, 7, 1), LocalDate.of(2027, 6, 30)))
                .thenReturn(List.of(fy2026));

        assertThatThrownBy(() -> service.openFiscalYear(
                new OpenFiscalYearRequest("CO", "FY26/27", 7, 2026)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void openFiscalYear_adjacentToAnExistingYear_isAllowed() {
        when(years.findOverlapping(COMPANY, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31)))
                .thenReturn(List.of());

        var dto = service.openFiscalYear(new OpenFiscalYearRequest("CO", "FY2027", 1, 2027));

        assertThat(dto.startDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(dto.endDate()).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    void openFiscalYear_badStartMonth_isAFriendly400_notA500() {
        assertThatThrownBy(() -> service.openFiscalYear(
                new OpenFiscalYearRequest("CO", "FY2027", 13, 2027)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 12");
    }

    // ---------------------------------------------------------------------------------------------
    // ACC-01: rollover keeps a year covering today and the year after it open
    // ---------------------------------------------------------------------------------------------

    private void existing(FiscalYear... ys) {
        List<FiscalYear> desc = new ArrayList<>(List.of(ys));
        desc.sort((a, b) -> b.getStartDate().compareTo(a.getStartDate()));
        when(years.findByCompanyIdOrderByStartDateDesc(COMPANY)).thenReturn(desc);
    }

    @Test
    void rollover_inOctober_opensNextCalendarYear_withTwelveMonthlyPeriods_andAuditsIt() {
        existing(year("FY2026", LocalDate.of(2026, 1, 1)));

        var created = service.ensureCurrentAndNextYear(COMPANY, LocalDate.of(2026, 10, 10));

        assertThat(created).singleElement().satisfies(y -> {
            assertThat(y.yearCode()).isEqualTo("FY2027");
            assertThat(y.startDate()).isEqualTo(LocalDate.of(2027, 1, 1));
            assertThat(y.endDate()).isEqualTo(LocalDate.of(2027, 12, 31));
            assertThat(y.startMonth()).isEqualTo(1);
        });
        org.mockito.ArgumentCaptor<FiscalPeriod> p = org.mockito.ArgumentCaptor.forClass(FiscalPeriod.class);
        verify(periods, org.mockito.Mockito.times(12)).save(p.capture());
        assertThat(p.getAllValues().get(0).getStartDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(p.getAllValues().get(11).getEndDate()).isEqualTo(LocalDate.of(2027, 12, 31));
        verify(audit).record(any(com.erp.platform.audit.AuditEvent.class));
    }

    @Test
    void rollover_isIdempotent_whenTheNextYearAlreadyExists() {
        existing(year("FY2026", LocalDate.of(2026, 1, 1)), year("FY2027", LocalDate.of(2027, 1, 1)));

        assertThat(service.ensureCurrentAndNextYear(COMPANY, LocalDate.of(2026, 10, 10))).isEmpty();
        verify(years, never()).save(any());
    }

    @Test
    void rollover_afterNewYearWithNothingOpened_catchesUp_currentAndNext() {
        existing(year("FY2026", LocalDate.of(2026, 1, 1)));

        var created = service.ensureCurrentAndNextYear(COMPANY, LocalDate.of(2027, 1, 2));

        assertThat(created).extracting(d -> d.yearCode()).containsExactly("FY2027", "FY2028");
    }

    @Test
    void rollover_julyToJuneYear_keepsItsStartMonth_andItsCodeConvention() {
        // FY2027 = 1 Jul 2026 – 30 Jun 2027
        existing(year("FY2027", LocalDate.of(2026, 7, 1)));

        var created = service.ensureCurrentAndNextYear(COMPANY, LocalDate.of(2026, 10, 10));

        assertThat(created).singleElement().satisfies(y -> {
            assertThat(y.yearCode()).isEqualTo("FY2028");
            assertThat(y.startDate()).isEqualTo(LocalDate.of(2027, 7, 1));
            assertThat(y.endDate()).isEqualTo(LocalDate.of(2028, 6, 30));
            assertThat(y.startMonth()).isEqualTo(7);
        });
    }

    @Test
    void rollover_takenCode_getsASuffix_insteadOfColliding() {
        existing(year("FY2026", LocalDate.of(2026, 1, 1)));
        when(years.findByCompanyIdAndYearCode(COMPANY, "FY2027"))
                .thenReturn(Optional.of(year("FY2027", LocalDate.of(2030, 1, 1))));

        var created = service.ensureCurrentAndNextYear(COMPANY, LocalDate.of(2026, 10, 10));

        assertThat(created).extracting(d -> d.yearCode()).containsExactly("FY2027-2");
    }

    @Test
    void rollover_codeWithoutATrailingYear_fallsBackToFyAndTheStartYear() {
        existing(year("Year One", LocalDate.of(2026, 1, 1)));

        var created = service.ensureCurrentAndNextYear(COMPANY, LocalDate.of(2026, 10, 10));

        assertThat(created).extracting(d -> d.yearCode()).containsExactly("FY2027");
    }

    @Test
    void rollover_neverCreatesAnOverlappingYear() {
        existing(year("FY2026", LocalDate.of(2026, 1, 1)));
        when(years.findOverlapping(eq(COMPANY), any(), any()))
                .thenReturn(List.of(year("X", LocalDate.of(2027, 3, 1))));

        assertThat(service.ensureCurrentAndNextYear(COMPANY, LocalDate.of(2026, 10, 10))).isEmpty();
        verify(years, never()).save(any());
    }

    @Test
    void rollover_companyWithNoCalendar_getsTheCurrentAndNextCalendarYears() {
        existing();

        var created = service.ensureCurrentAndNextYear(COMPANY, LocalDate.of(2026, 10, 10));

        assertThat(created).extracting(d -> d.yearCode()).containsExactly("FY2026", "FY2027");
    }

    @Test
    void rollover_dateBeforeTheFirstYear_isLeftToTheAccountant() {
        existing(year("FY2026", LocalDate.of(2026, 1, 1)));

        assertThat(service.ensureCurrentAndNextYear(COMPANY, LocalDate.of(2025, 6, 1))).isEmpty();
        verify(years, never()).save(any());
    }

    @Test
    void ensureFollowingYear_opensTheSuccessor_onlyWhenNoneExists() {
        FiscalYear fy2026 = year("FY2026", LocalDate.of(2026, 1, 1));
        when(years.findByUid("Y26")).thenReturn(Optional.of(fy2026));
        existing(fy2026);

        assertThat(service.ensureFollowingYear("Y26")).hasValueSatisfying(
                y -> assertThat(y.yearCode()).isEqualTo("FY2027"));

        existing(fy2026, year("FY2027", LocalDate.of(2027, 1, 1)));
        assertThat(service.ensureFollowingYear("Y26")).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // ACC-15: a period of a CLOSED year cannot be reopened on its own
    // ---------------------------------------------------------------------------------------------

    @Test
    void reopenPeriod_inAClosedYear_isRefused_andPointsToReopeningTheYear() {
        FiscalYear fy2026 = year("FY2026", LocalDate.of(2026, 1, 1));
        fy2026.setStatus(com.erp.modules.gl.domain.enums.PeriodStatus.CLOSED);
        FiscalPeriod december = closedPeriod();
        when(periods.findByUid("P12")).thenReturn(Optional.of(december));
        when(years.findByCompanyIdAndId(anyLong(), any())).thenReturn(Optional.of(fy2026));

        assertThatThrownBy(() -> service.reopenPeriod("P12"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("FY2026")
                .hasMessageContaining("Reopen the fiscal year first");
        assertThat(december.getStatus()).isEqualTo(com.erp.modules.gl.domain.enums.PeriodStatus.CLOSED);
    }

    @Test
    void reopenPeriod_inAnOpenYear_reopens() {
        FiscalYear fy2026 = year("FY2026", LocalDate.of(2026, 1, 1));
        FiscalPeriod december = closedPeriod();
        when(periods.findByUid("P12")).thenReturn(Optional.of(december));
        when(years.findByCompanyIdAndId(anyLong(), any())).thenReturn(Optional.of(fy2026));

        var dto = service.reopenPeriod("P12");

        assertThat(dto.status()).isEqualTo(com.erp.modules.gl.domain.enums.PeriodStatus.OPEN);
    }

    // ---------------------------------------------------------------------------------------------

    private static FiscalPeriod closedPeriod() {
        FiscalPeriod p = new FiscalPeriod(COMPANY, 1L, 12,
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), null);
        p.setStatus(com.erp.modules.gl.domain.enums.PeriodStatus.CLOSED);
        return p;
    }

    static FiscalYear year(String code, LocalDate start) {
        return new FiscalYear(COMPANY, code, start.getMonthValue(), start,
                start.plusMonths(12).minusDays(1), null);
    }
}
