package com.erp.modules.gl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.domain.entity.FiscalYear;
import com.erp.modules.gl.domain.enums.PeriodStatus;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
import com.erp.modules.gl.repository.FiscalYearRepository;
import com.erp.platform.common.api.AccountingSetupException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ACC-29: "no period set up" and "period closed" are different problems with different fixes, and
 * the message must say which — naming the closed period. ACC-09 legacy: two open periods on one
 * date are refused clearly instead of blowing up as a non-unique result.
 */
class FiscalPeriodResolverTest {

    private static final long COMPANY = 7L;
    private static final LocalDate DATE = LocalDate.of(2026, 10, 15);

    private final FiscalPeriodRepository periods = mock(FiscalPeriodRepository.class);
    private final FiscalYearRepository years = mock(FiscalYearRepository.class);
    private final FiscalPeriodResolver resolver = new FiscalPeriodResolver(periods, years);

    @Test
    void singleOpenPeriod_isReturned() {
        FiscalPeriod october = period(10, PeriodStatus.OPEN);
        when(periods.findAllCoveringDate(COMPANY, DATE)).thenReturn(List.of(october));

        assertThat(resolver.resolveOpen(COMPANY, DATE)).isSameAs(october);
    }

    @Test
    void noPeriod_saysNothingIsSetUpForTheDate() {
        when(periods.findAllCoveringDate(COMPANY, DATE)).thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolveOpen(COMPANY, DATE))
                .isInstanceOf(AccountingSetupException.class)
                .hasMessageContaining("No fiscal period is set up for 15 Oct 2026")
                .hasMessageNotContaining("closed")
                .satisfies(ex -> assertThat(((AccountingSetupException) ex).userMessage())
                        .isEqualTo(AccountingSetupException.DEFAULT_USER_MESSAGE));
    }

    @Test
    void closedPeriod_isNamed_andNotDescribedAsMissing() {
        FiscalPeriod october = period(10, PeriodStatus.CLOSED);
        when(periods.findAllCoveringDate(COMPANY, DATE)).thenReturn(List.of(october));
        FiscalYear fy = new FiscalYear(COMPANY, "FY2026", 1,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null);
        when(years.findById(any())).thenReturn(Optional.of(fy));

        assertThatThrownBy(() -> resolver.resolveOpen(COMPANY, DATE))
                .isInstanceOf(AccountingSetupException.class)
                .hasMessageContaining("October 2026 (period 10 of FY2026)")
                .hasMessageContaining("is closed")
                .hasMessageNotContaining("set up")
                .satisfies(ex -> assertThat(((AccountingSetupException) ex).userMessage())
                        .contains("is closed")
                        .contains("Ask your accountant")
                        .doesNotContain("FY2026"));
    }

    @Test
    void overlappingOpenPeriods_areRefusedClearly_notPickedAtRandom() {
        when(periods.findAllCoveringDate(COMPANY, DATE))
                .thenReturn(List.of(period(10, PeriodStatus.OPEN), period(4, PeriodStatus.OPEN)));

        assertThatThrownBy(() -> resolver.resolveOpen(COMPANY, DATE))
                .isInstanceOf(AccountingSetupException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    void anOpenPeriodWins_overAClosedOverlappingOne() {
        FiscalPeriod open = period(4, PeriodStatus.OPEN);
        when(periods.findAllCoveringDate(COMPANY, DATE))
                .thenReturn(List.of(period(10, PeriodStatus.CLOSED), open));

        assertThat(resolver.resolveOpen(COMPANY, DATE)).isSameAs(open);
    }

    private static FiscalPeriod period(int no, PeriodStatus status) {
        FiscalPeriod p = new FiscalPeriod(COMPANY, 3L, no,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null);
        p.setStatus(status);
        return p;
    }
}
