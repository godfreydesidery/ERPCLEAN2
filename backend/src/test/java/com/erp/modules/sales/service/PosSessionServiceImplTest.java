package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingSafeInvoker;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.sales.domain.dto.CloseSessionRequest;
import com.erp.modules.sales.domain.dto.ReconcileSessionRequest;
import com.erp.modules.sales.domain.entity.PosSession;
import com.erp.modules.sales.domain.enums.PosSessionStatus;
import com.erp.modules.sales.repository.PosSessionPayoutRepository;
import com.erp.modules.sales.repository.PosSessionRepository;
import com.erp.modules.sales.repository.PosTillRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link PosSessionServiceImpl} covering the adversarial-review findings:
 * <ul>
 *   <li>Finding 2+4: payouts subtract from expected cash (REFUND/PAID_OUT semantics)</li>
 *   <li>Finding 3: variance GL uses company base currency, not hardcoded USD</li>
 *   <li>Finding 5: variance GL posting date = session closed_at date, not today</li>
 *   <li>Finding 6: missing GL config propagates (fails the reconcile command)</li>
 * </ul>
 */
class PosSessionServiceImplTest {

    private PosSessionRepository       sessions;
    private PosTillRepository          tills;
    private PosSessionPayoutRepository payouts;
    private SalesInvoiceRepository     invoices;
    private GLPostingSafeInvoker       glInvoker;
    private GLConfigResolver           glConfig;
    private CompanyRepository          companies;
    private ScopeGuard                 scopeGuard;
    private AuditService               audit;
    private SalesDepthNumberGenerator  numberGen;
    private PosSessionServiceImpl      service;

    @BeforeEach
    void setUp() {
        sessions   = mock(PosSessionRepository.class);
        tills      = mock(PosTillRepository.class);
        payouts    = mock(PosSessionPayoutRepository.class);
        invoices   = mock(SalesInvoiceRepository.class);
        glInvoker  = mock(GLPostingSafeInvoker.class);
        glConfig   = mock(GLConfigResolver.class);
        companies  = mock(CompanyRepository.class);
        scopeGuard = mock(ScopeGuard.class);
        audit      = mock(AuditService.class);
        numberGen  = mock(SalesDepthNumberGenerator.class);
        when(numberGen.nextPosSession(anyLong())).thenReturn("POS-0001");
        service = new PosSessionServiceImpl(sessions, tills, payouts, invoices,
                glInvoker, glConfig, companies, scopeGuard, audit, numberGen);

        // default: no request context
        RequestContext.set(new RequestContext.Principal(99L, "cashier", false, 1L, 1L, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Finding 2+4: payouts SUBTRACT from expected cash
    // -------------------------------------------------------------------------

    /**
     * Drawer arithmetic: opening=1000, cashSales=500, payouts=200
     * expected = 1000 + 500 - 200 = 1300 (NOT 1000+500+200=1700)
     */
    @Test
    void closeSession_expectedCash_subtractsPayouts() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        session.setStatus(PosSessionStatus.OPEN);

        when(sessions.findByUid("S1")).thenReturn(Optional.of(session));
        when(invoices.sumGrossByPosSession(session.getId())).thenReturn(new BigDecimal("500.00"));
        when(payouts.totalPayoutsForSession(session.getId())).thenReturn(new BigDecimal("200.00"));
        when(sessions.save(any())).thenReturn(session);

        var req = new CloseSessionRequest(new BigDecimal("1290.00"), null);
        var dto = service.closeSession("S1", req);

        // expected = 1000 + 500 - 200 = 1300
        assertThat(session.getExpectedCashAmount())
                .isEqualByComparingTo(new BigDecimal("1300.00"));
        // variance = counted(1290) - expected(1300) = -10 (short)
        assertThat(session.getVarianceAmount())
                .isEqualByComparingTo(new BigDecimal("-10.00"));
    }

    /**
     * Zero payouts: expected = opening + cashSales
     */
    @Test
    void closeSession_zeroPayouts_expectedIsOpeningPlusSales() {
        PosSession session = openSession(1L, new BigDecimal("500.00"));

        when(sessions.findByUid("S2")).thenReturn(Optional.of(session));
        when(invoices.sumGrossByPosSession(session.getId())).thenReturn(new BigDecimal("800.00"));
        when(payouts.totalPayoutsForSession(session.getId())).thenReturn(BigDecimal.ZERO);
        when(sessions.save(any())).thenReturn(session);

        service.closeSession("S2", new CloseSessionRequest(new BigDecimal("1300.00"), null));

        assertThat(session.getExpectedCashAmount())
                .isEqualByComparingTo(new BigDecimal("1300.00"));
        assertThat(session.getVarianceAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // -------------------------------------------------------------------------
    // Finding 3: GL variance uses company base currency, not USD
    // -------------------------------------------------------------------------

    @Test
    void reconcileSession_varianceGl_usesCompanyBaseCurrency() {
        PosSession session = closedSession(1L,
                new BigDecimal("200.00"),   // variance > 0 = OVER
                Instant.parse("2026-06-12T22:00:00Z"));

        Company company = mockCompany(1L, "TZS");
        when(sessions.findByUid("S3")).thenReturn(Optional.of(session));
        when(companies.findById(1L)).thenReturn(Optional.of(company));
        ChartOfAccount cashAcct = mockCoa(10L);
        ChartOfAccount overAcct = mockCoa(20L);
        when(glConfig.resolve(1L, GlConfigKey.CASH)).thenReturn(cashAcct);
        when(glConfig.resolve(1L, GlConfigKey.POS_CASH_OVER)).thenReturn(overAcct);
        when(glInvoker.postInNewTx(any())).thenReturn(new JournalEntryDto(99L, null, null, null, null, null, null, null, null, null, null, null));
        when(sessions.save(any())).thenReturn(session);

        service.reconcileSession("S3", new ReconcileSessionRequest(null));

        ArgumentCaptor<JournalEntryDraft> captor = ArgumentCaptor.forClass(JournalEntryDraft.class);
        verify(glInvoker).postInNewTx(captor.capture());
        JournalEntryDraft draft = captor.getValue();

        // All lines must carry TZS (company currency), not USD
        draft.lines().forEach(line ->
                assertThat(line.currency()).as("line currency").isEqualTo("TZS")
        );
    }

    // -------------------------------------------------------------------------
    // Finding 5: variance GL posting date = session closed_at date
    // -------------------------------------------------------------------------

    @Test
    void reconcileSession_varianceGl_postingDateEqualsSessionCloseDate() {
        // session closed on 2026-06-12 (UTC)
        Instant closedAt = Instant.parse("2026-06-12T22:00:00Z");
        PosSession session = closedSession(1L, new BigDecimal("-100.00"), closedAt);

        Company company = mockCompany(1L, "TZS");
        when(sessions.findByUid("S4")).thenReturn(Optional.of(session));
        when(companies.findById(1L)).thenReturn(Optional.of(company));
        ChartOfAccount cashAcct  = mockCoa(10L);
        ChartOfAccount shortAcct = mockCoa(30L);
        when(glConfig.resolve(1L, GlConfigKey.CASH)).thenReturn(cashAcct);
        when(glConfig.resolve(1L, GlConfigKey.POS_CASH_SHORT)).thenReturn(shortAcct);
        when(glInvoker.postInNewTx(any())).thenReturn(new JournalEntryDto(99L, null, null, null, null, null, null, null, null, null, null, null));
        when(sessions.save(any())).thenReturn(session);

        service.reconcileSession("S4", new ReconcileSessionRequest(null));

        ArgumentCaptor<JournalEntryDraft> captor = ArgumentCaptor.forClass(JournalEntryDraft.class);
        verify(glInvoker).postInNewTx(captor.capture());

        // Must post on 2026-06-12, not today
        assertThat(captor.getValue().postingDate()).isEqualTo(LocalDate.of(2026, 6, 12));
    }

    // -------------------------------------------------------------------------
    // Finding 6: missing GL config propagates (no silent null return)
    // -------------------------------------------------------------------------

    @Test
    void reconcileSession_missingGlConfig_propagatesException() {
        PosSession session = closedSession(1L, new BigDecimal("50.00"),
                Instant.parse("2026-06-12T22:00:00Z"));

        Company company = mockCompany(1L, "TZS");
        when(sessions.findByUid("S5")).thenReturn(Optional.of(session));
        when(companies.findById(1L)).thenReturn(Optional.of(company));
        // POS_CASH_OVER config missing — throws
        ChartOfAccount cashAcctS5 = mockCoa(10L);
        when(glConfig.resolve(1L, GlConfigKey.CASH)).thenReturn(cashAcctS5);
        when(glConfig.resolve(1L, GlConfigKey.POS_CASH_OVER))
                .thenThrow(new NotFoundException("GL config POS_CASH_OVER not found"));

        // The exception must propagate out of reconcileSession — no silent swallow
        assertThatThrownBy(() -> service.reconcileSession("S5", new ReconcileSessionRequest(null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("POS_CASH_OVER");

        // GL must NOT have been posted (exception fired before postInNewTx)
        verify(glInvoker, never()).postInNewTx(any());
    }

    // -------------------------------------------------------------------------
    // Variance = 0 means no GL post
    // -------------------------------------------------------------------------

    @Test
    void reconcileSession_zeroVariance_noGlPost() {
        PosSession session = closedSession(1L, BigDecimal.ZERO,
                Instant.parse("2026-06-12T22:00:00Z"));

        when(sessions.findByUid("S6")).thenReturn(Optional.of(session));
        when(sessions.save(any())).thenReturn(session);
        when(invoices.sumGrossByPosSession(any())).thenReturn(BigDecimal.ZERO);
        when(payouts.totalPayoutsForSession(any())).thenReturn(BigDecimal.ZERO);

        service.reconcileSession("S6", new ReconcileSessionRequest(null));

        verify(glInvoker, never()).postInNewTx(any());
        assertThat(session.getVarianceJournalId()).isNull();
    }

    // -------------------------------------------------------------------------
    // Balanced journal: DR == CR for both over and short
    // -------------------------------------------------------------------------

    @Test
    void reconcileSession_overVariance_journalBalances() {
        BigDecimal variance = new BigDecimal("150.00");
        PosSession session = closedSession(1L, variance,
                Instant.parse("2026-06-12T22:00:00Z"));

        Company company = mockCompany(1L, "TZS");
        when(sessions.findByUid("S7")).thenReturn(Optional.of(session));
        when(companies.findById(1L)).thenReturn(Optional.of(company));
        ChartOfAccount cashAcctS7 = mockCoa(10L);
        ChartOfAccount overAcctS7 = mockCoa(20L);
        when(glConfig.resolve(1L, GlConfigKey.CASH)).thenReturn(cashAcctS7);
        when(glConfig.resolve(1L, GlConfigKey.POS_CASH_OVER)).thenReturn(overAcctS7);
        when(glInvoker.postInNewTx(any())).thenReturn(new JournalEntryDto(99L, null, null, null, null, null, null, null, null, null, null, null));
        when(sessions.save(any())).thenReturn(session);

        service.reconcileSession("S7", new ReconcileSessionRequest(null));

        ArgumentCaptor<JournalEntryDraft> captor = ArgumentCaptor.forClass(JournalEntryDraft.class);
        verify(glInvoker).postInNewTx(captor.capture());
        JournalEntryDraft draft = captor.getValue();

        BigDecimal totalDebit  = draft.lines().stream()
                .map(l -> l.debitAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = draft.lines().stream()
                .map(l -> l.creditAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).isEqualByComparingTo(totalCredit);
        assertThat(totalDebit).isEqualByComparingTo(variance);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PosSession openSession(Long companyId, BigDecimal openingFloat) {
        PosSession s = new PosSession(companyId, 1L, 5L, 99L, "POS-0001", openingFloat, 99L);
        s.setStatus(PosSessionStatus.OPEN);
        return s;
    }

    private PosSession closedSession(Long companyId, BigDecimal variance, Instant closedAt) {
        PosSession s = new PosSession(companyId, 1L, 5L, 99L, "POS-0001", new BigDecimal("500.00"), 99L);
        s.setStatus(PosSessionStatus.CLOSED);
        s.setClosedAt(closedAt);
        s.setVarianceAmount(variance);
        s.setExpectedCashAmount(new BigDecimal("500.00"));
        s.setCountedCashAmount(new BigDecimal("500.00").add(variance));
        return s;
    }

    private Company mockCompany(Long id, String currency) {
        Company c = mock(Company.class);
        when(c.getId()).thenReturn(id);
        when(c.getBaseCurrency()).thenReturn(currency);
        return c;
    }

    private ChartOfAccount mockCoa(Long id) {
        ChartOfAccount coa = mock(ChartOfAccount.class);
        when(coa.getId()).thenReturn(id);
        return coa;
    }
}
