package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.stock.domain.dto.StockValuationReportDto;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.modules.stock.service.StockValuationQuery;
import com.erp.platform.security.RequestContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code asOf} handling on GET /api/v1/stock/valuation/report.
 *
 * <p>The report reads {@code stock_on_hand}, a maintained CURRENT projection: today is the only
 * date it can answer. A PAST date was already rejected. A FUTURE date was not — it returned today's
 * numbers with a 200 OK under a heading claiming a date that has not happened, which is the same
 * silent lie the past-date guard exists to prevent. Both are now 400s.
 */
class StockValuationControllerAsOfTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 8);

    private StockValuationQuery valuationQuery;
    private StockValuationController controller;

    @BeforeEach
    void setUp() {
        valuationQuery = mock(StockValuationQuery.class);
        InventoryValuationService valuationService = mock(InventoryValuationService.class);
        Clock fixed = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        controller = new StockValuationController(valuationQuery, valuationService, fixed);

        when(valuationQuery.report(anyLong())).thenReturn(mock(StockValuationReportDto.class));
        RequestContext.set(new RequestContext.Principal(1L, "tester", true, 5L, 9L, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void futureAsOf_isRejected() {
        LocalDate future = TODAY.plusDays(1);

        assertThatThrownBy(() -> controller.report(future))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current position");
    }

    @Test
    void farFutureAsOf_isRejected() {
        assertThatThrownBy(() -> controller.report(TODAY.plusYears(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void futureAsOfMessage_saysWhatToDoInstead_andLeaksNoInternalDetail() {
        String message = catchMessage(TODAY.plusDays(30));

        assertThat(message).contains("leave the date blank");
        // Error-message hygiene: user-facing text only, no table/column/exception vocabulary.
        assertThat(message).doesNotContain("stock_on_hand").doesNotContain("Exception");
    }

    @Test
    void pastAsOf_isStillRejected() {
        assertThatThrownBy(() -> controller.report(TODAY.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Stock Movement report");
    }

    @Test
    void todayAsOf_isAccepted() {
        assertThatCode(() -> controller.report(TODAY)).doesNotThrowAnyException();
    }

    @Test
    void noAsOf_isAccepted() {
        assertThatCode(() -> controller.report(null)).doesNotThrowAnyException();
    }

    /**
     * The clock is injected, so "future" must be measured against it and not the wall clock —
     * otherwise the guard silently changes behaviour with the machine's date.
     */
    @Test
    void futureIsMeasuredAgainstTheInjectedClock_notTheWallClock() {
        Clock stuckInThePast = Clock.fixed(
                Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);
        StockValuationController pastController = new StockValuationController(
                valuationQuery, mock(InventoryValuationService.class), stuckInThePast);

        // 2020-01-02 is the future for THIS clock even though it is long past in real time.
        assertThatThrownBy(() -> pastController.report(LocalDate.of(2020, 1, 2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> pastController.report(LocalDate.of(2020, 1, 1)))
                .doesNotThrowAnyException();
    }

    private String catchMessage(LocalDate asOf) {
        try {
            controller.report(asOf);
            throw new AssertionError("expected the request to be rejected");
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }
}
