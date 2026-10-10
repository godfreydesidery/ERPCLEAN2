package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.common.money.CurrencyMinorUnits;
import com.erp.platform.common.time.BusinessZone;
import com.erp.platform.common.time.CompanyCalendar;
import com.erp.platform.security.ScopeGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ACC-03 / SAL-23 — the VAT output window for a return period is the COMPANY's calendar month
 * (owner ruling 2026-10-10), not the UTC month. A sale at 00:30 EAT on 1 November (21:30 UTC on
 * 31 October) is November output VAT.
 */
@ExtendWith(MockitoExtension.class)
class SalesVatWindowTimeZoneTest {

    @Mock SalesInvoiceRepository invoices;
    @Mock CompanyRepository companies;
    @Mock CurrencyMinorUnits minorUnits;
    @Mock ScopeGuard scopeGuard;
    @Spy CompanyCalendar calendar = CompanyCalendar.fixed(BusinessZone.DEFAULT, Clock.systemUTC());
    @InjectMocks SalesInvoiceServiceImpl service;

    @Test
    void novembersReturn_coversNovemberInEat_soThe0030SaleOnThe1stIsInIt() {
        when(invoices.findFinalisedInPeriod(eq(1L), any(), any())).thenReturn(List.of());

        service.findVatSummaryForPeriod(1L, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));

        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> end   = ArgumentCaptor.forClass(Instant.class);
        verify(invoices).findFinalisedInPeriod(eq(1L), start.capture(), end.capture());

        // [1 Nov 00:00 EAT, 1 Dec 00:00 EAT) == [31 Oct 21:00 UTC, 30 Nov 21:00 UTC)
        assertThat(start.getValue()).isEqualTo(Instant.parse("2026-10-31T21:00:00Z"));
        assertThat(end.getValue()).isEqualTo(Instant.parse("2026-11-30T21:00:00Z"));

        Instant barSale = Instant.parse("2026-10-31T21:30:00Z");   // 00:30 EAT, 1 November
        assertThat(barSale).isAfterOrEqualTo(start.getValue()).isBefore(end.getValue());
    }

    @Test
    void octobersReturn_endsAtMidnightEat_andExcludesThe0030Sale() {
        when(invoices.findFinalisedInPeriod(eq(1L), any(), any())).thenReturn(List.of());

        service.findVatSummaryForPeriod(1L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        verify(invoices).findFinalisedInPeriod(eq(1L), any(), end.capture());
        assertThat(Instant.parse("2026-10-31T21:30:00Z")).isAfterOrEqualTo(end.getValue());
    }
}
