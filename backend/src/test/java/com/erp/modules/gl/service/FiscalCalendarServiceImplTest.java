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
    // ACC-15: a period of a CLOSED year cannot be reopened on its own
    // ---------------------------------------------------------------------------------------------

    @Test
    void reopenPeriod_inAClosedYear_isRefused_andPointsToReopeningTheYear() {
        FiscalYear fy2026 = year("FY2026", LocalDate.of(2026, 1, 1));
        fy2026.setStatus(com.erp.modules.gl.domain.enums.PeriodStatus.CLOSED);
        FiscalPeriod december = closedPeriod();
        when(periods.findByUid("P12")).thenReturn(Optional.of(december));
        when(years.findById(any())).thenReturn(Optional.of(fy2026));

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
        when(years.findById(any())).thenReturn(Optional.of(fy2026));

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
