package com.erp.modules.gl.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.erp.modules.gl.service.GLPostingSafeInvoker;
import com.erp.modules.sales.domain.dto.InvoicePostingTotalsDto;
import com.erp.modules.sales.domain.dto.SaleFinalisedPayload;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.platform.common.time.BusinessZone;
import com.erp.platform.common.time.CompanyCalendar;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * ACC-03 / SAL-23 / LSF-15 — the GL posting date of a sale is the COMPANY's date of the moment it
 * was finalised (owner ruling 2026-10-10), not its UTC date. A bar sale at 00:30 EAT on 1 November
 * is 21:30 UTC on 31 October; it used to post to October (and October's VAT return).
 */
class SalesPostingHandlerTimeZoneTest {

    private static final Long COMPANY_ID = 1L;
    private static final String INVOICE_UID = "01INVOICE0000000000000001";

    private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
    private final SalesInvoiceService invoices = mock(SalesInvoiceService.class);
    private final GLPostingSafeInvoker invoker = mock(GLPostingSafeInvoker.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SalesPostingHandler handler = new SalesPostingHandler(guard, invoices, invoker,
            mapper, CompanyCalendar.fixed(BusinessZone.DEFAULT, Clock.systemUTC()));

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    @Test
    void aSaleAt0030EatOnThe1st_postsOnThe1st_notTheLastDayOfThePreviousMonth() throws Exception {
        assertThat(postingDateFor(Instant.parse("2026-10-31T21:30:00Z")))
                .isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void anEveningSale_staysOnItsOwnDay() throws Exception {
        // 23:59 EAT on 31 October == 20:59 UTC — October either way.
        assertThat(postingDateFor(Instant.parse("2026-10-31T20:59:00Z")))
                .isEqualTo(LocalDate.of(2026, 10, 31));
    }

    private LocalDate postingDateFor(Instant finalisedAt) throws Exception {
        InvoicePostingTotalsDto totals = mock(InvoicePostingTotalsDto.class);
        when(totals.status()).thenReturn("FINALISED");
        when(totals.finalisedAt()).thenReturn(finalisedAt);
        when(invoices.findPostingTotalsByUidAndCompany(INVOICE_UID, COMPANY_ID))
                .thenReturn(Optional.of(totals));
        when(guard.alreadyProcessed(anyString(), anyString())).thenReturn(false);

        DomainEvent event = mock(DomainEvent.class);
        when(event.getUid()).thenReturn("01EVENT00000000000000000001");
        when(event.getCompanyId()).thenReturn(COMPANY_ID);
        when(event.getBranchId()).thenReturn(10L);
        when(event.getPayload()).thenReturn(mapper.writeValueAsString(new SaleFinalisedPayload(
                INVOICE_UID, COMPANY_ID, 10L, finalisedAt, List.of(), true, "INV-0001")));

        handler.handle(event);

        // Signature-agnostic: the posting date is the one LocalDate handed to the poster.
        return mockingDetails(invoker).getInvocations().stream()
                .filter(i -> i.getMethod().getName().startsWith("postSale"))
                .flatMap(i -> Arrays.stream(i.getArguments()))
                .filter(LocalDate.class::isInstance)
                .map(LocalDate.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the sale was not posted"));
    }
}
