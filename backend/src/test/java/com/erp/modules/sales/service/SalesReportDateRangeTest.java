package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.ScopeGuard;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** RPT-29: the Sales Report refuses an end date before its start date, like its siblings. */
class SalesReportDateRangeTest {

    private final SalesReportQuery query = new SalesReportQuery(
            mock(JdbcTemplate.class), mock(ScopeGuard.class), mock(BranchReadGuard.class));

    @Test
    void endBeforeStartIsRefused() {
        assertThatThrownBy(() -> query.report(1L, LocalDate.of(2026, 10, 10),
                LocalDate.of(2026, 10, 1), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The end date cannot be before the start date.");
    }

    @Test
    void missingDatesAreRefused() {
        assertThatThrownBy(() -> query.report(1L, null, LocalDate.of(2026, 10, 1),
                null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Choose the dates this report should cover.");
    }
}
