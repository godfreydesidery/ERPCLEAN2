package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingSafeInvoker;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.service.StepUpAuthService;
import com.erp.modules.sales.domain.dto.CloseSessionRequest;
import com.erp.modules.sales.domain.dto.PayoutSubtotalDto;
import com.erp.modules.sales.domain.dto.PosExpenseRequest;
import com.erp.modules.sales.domain.dto.PosPayoutDto;
import com.erp.modules.sales.domain.dto.PosPayoutRequest;
import com.erp.modules.sales.domain.dto.ReconcileSessionRequest;
import com.erp.modules.sales.domain.dto.TenderSubtotalDto;
import com.erp.modules.sales.domain.dto.TillExpenseCategoryTotalDto;
import com.erp.modules.sales.domain.dto.ZReadDto;
import com.erp.modules.sales.domain.entity.PosExpenseIdempotency;
import com.erp.modules.sales.domain.entity.PosSession;
import com.erp.modules.sales.domain.entity.PosSessionPayout;
import com.erp.modules.sales.domain.enums.PosPayoutType;
import com.erp.modules.sales.domain.enums.PosSessionStatus;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.repository.PosExpenseIdempotencyRepository;
import com.erp.modules.sales.repository.PosSessionPayoutRepository;
import com.erp.modules.sales.repository.PosSessionRepository;
import com.erp.modules.sales.repository.PosTillRepository;
import com.erp.modules.sales.repository.SalesInvoicePaymentRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    /** GL account ids the K8 payout posting is expected to hit. */
    private static final Long CASH_ACCT_ID    = 1000L;
    /** 5175 "Till Expenses" — the V97 POS_TILL_EXPENSE account, NOT 5170 Cash Short. */
    private static final Long EXPENSE_ACCT_ID = 5175L;
    /** 5170 "Cash Short / Till Shortage" — the variance account an expense must never touch. */
    private static final Long CASH_SHORT_ACCT_ID = 5170L;

    private PosSessionRepository       sessions;
    private PosTillRepository          tills;
    private PosSessionPayoutRepository payouts;
    private PosExpenseIdempotencyRepository expenseIdempotency;
    private SalesInvoiceRepository     invoices;
    private SalesInvoicePaymentRepository tenderPayments;
    private GLPostingSafeInvoker       glInvoker;
    private GLConfigResolver           glConfig;
    private GLPostingService           glPosting;
    private CompanyRepository          companies;
    private ScopeGuard                 scopeGuard;
    private AuditService               audit;
    private SalesDepthNumberGenerator  numberGen;
    private TillExpenseGlSeeder        tillExpenseGl;
    private PermissionResolver         permissionResolver;
    private StepUpAuthService          stepUpAuth;
    private PosSessionServiceImpl      service;

    @BeforeEach
    void setUp() {
        sessions   = mock(PosSessionRepository.class);
        tills      = mock(PosTillRepository.class);
        payouts    = mock(PosSessionPayoutRepository.class);
        expenseIdempotency = mock(PosExpenseIdempotencyRepository.class);
        invoices   = mock(SalesInvoiceRepository.class);
        tenderPayments = mock(SalesInvoicePaymentRepository.class);
        glInvoker  = mock(GLPostingSafeInvoker.class);
        glConfig   = mock(GLConfigResolver.class);
        glPosting  = mock(GLPostingService.class);
        companies  = mock(CompanyRepository.class);
        scopeGuard = mock(ScopeGuard.class);
        audit      = mock(AuditService.class);
        numberGen  = mock(SalesDepthNumberGenerator.class);
        tillExpenseGl = mock(TillExpenseGlSeeder.class);
        permissionResolver = mock(PermissionResolver.class);
        stepUpAuth = mock(StepUpAuthService.class);
        when(numberGen.nextPosSession(anyLong())).thenReturn("POS-0001");
        // Left unstubbed on purpose: these tests drive the permission-gated GET paths, which never
        // consult either collaborator. The manager-approval path has its own suite
        // (PosSessionAuthorisedReadTest) where both are stubbed per case.
        service = new PosSessionServiceImpl(sessions, tills, payouts, expenseIdempotency, invoices,
                tenderPayments, glInvoker, glConfig, glPosting, companies, scopeGuard, audit,
                numberGen, tillExpenseGl, permissionResolver, stepUpAuth);

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
        when(tenderPayments.sumCashTenderByPosSession(session.getId())).thenReturn(new BigDecimal("500.00"));
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
        when(tenderPayments.sumCashTenderByPosSession(session.getId())).thenReturn(new BigDecimal("800.00"));
        when(payouts.totalPayoutsForSession(session.getId())).thenReturn(BigDecimal.ZERO);
        when(sessions.save(any())).thenReturn(session);

        service.closeSession("S2", new CloseSessionRequest(new BigDecimal("1300.00"), null));

        assertThat(session.getExpectedCashAmount())
                .isEqualByComparingTo(new BigDecimal("1300.00"));
        assertThat(session.getVarianceAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // -------------------------------------------------------------------------
    // Busy-day-sim FIX 1 (CRITICAL): mixed CASH + MOBILE_MONEY tenders — only the CASH leg
    // belongs in the expected-cash arithmetic. Before the fix, expected cash was computed from
    // invoice GROSS (all tenders), inflating expected cash by the non-cash leg and producing a
    // phantom shortage on an honest count.
    // -------------------------------------------------------------------------

    @Test
    void closeSession_mixedTenders_expectedCashExcludesMobileMoneyLeg_varianceZeroOnHonestCount() {
        // Session turnover 800 = 500 CASH + 300 MOBILE_MONEY. Only the 500 CASH ever entered the
        // drawer, so expected cash = opening(1000) + 500 = 1500, NOT opening + 800(gross) = 1800.
        PosSession session = openSession(1L, new BigDecimal("1000.00"));

        when(sessions.findByUid("S8")).thenReturn(Optional.of(session));
        when(invoices.sumGrossByPosSession(session.getId())).thenReturn(new BigDecimal("800.00"));
        when(tenderPayments.sumCashTenderByPosSession(session.getId())).thenReturn(new BigDecimal("500.00"));
        when(payouts.totalPayoutsForSession(session.getId())).thenReturn(BigDecimal.ZERO);
        when(sessions.save(any())).thenReturn(session);

        // Cashier counts the drawer honestly: exactly opening + CASH tenders = 1500.
        service.closeSession("S8", new CloseSessionRequest(new BigDecimal("1500.00"), null));

        assertThat(session.getExpectedCashAmount())
                .as("expected cash must exclude the MOBILE_MONEY leg — only CASH enters the drawer")
                .isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(session.getVarianceAmount())
                .as("an honest count against the correct expected figure must show zero variance, "
                        + "not a phantom shortage from folding in the MOBILE_MONEY leg")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void xRead_mixedTenders_reportsGrossTurnoverSeparatelyFromCashTender() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));

        when(sessions.findByUid("S9")).thenReturn(Optional.of(session));
        when(invoices.sumGrossByPosSession(session.getId())).thenReturn(new BigDecimal("800.00"));
        when(tenderPayments.sumCashTenderByPosSession(session.getId())).thenReturn(new BigDecimal("500.00"));
        when(payouts.totalPayoutsForSession(session.getId())).thenReturn(BigDecimal.ZERO);
        when(invoices.countByPosSession(session.getId())).thenReturn(2L);
        when(tenderPayments.sumByPosSessionGroupedByTender(session.getId()))
                .thenReturn(List.of(
                        new TenderSubtotalDto(TenderType.CASH, new BigDecimal("500.00")),
                        new TenderSubtotalDto(TenderType.MOBILE_MONEY, new BigDecimal("300.00"))));

        var xRead = service.xRead("S9");

        assertThat(xRead.totalSalesAmount())
                .as("totalSalesAmount is gross turnover across all tenders — reporting only")
                .isEqualByComparingTo(new BigDecimal("800.00"));
        assertThat(xRead.cashTenderAmount())
                .as("cashTenderAmount is the CASH-only figure that feeds expected cash")
                .isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(xRead.expectedCashAmount())
                .as("expected cash = opening + CASH tender only, never the gross turnover")
                .isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(xRead.tenderSubtotals()).hasSize(2);
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
        when(glInvoker.postInNewTx(any())).thenReturn(new JournalEntryDto(99L, null, null, null, null, null, null, null, null, null, null, false, null, null, null, null, null));
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
        when(glInvoker.postInNewTx(any())).thenReturn(new JournalEntryDto(99L, null, null, null, null, null, null, null, null, null, null, false, null, null, null, null, null));
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
        when(tenderPayments.sumCashTenderByPosSession(any())).thenReturn(BigDecimal.ZERO);
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
        when(glInvoker.postInNewTx(any())).thenReturn(new JournalEntryDto(99L, null, null, null, null, null, null, null, null, null, null, false, null, null, null, null, null));
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
    // Re-readable Z-read (K1/K8): reconciling is one-shot, so the report must be
    // recomputable from persisted data or it is lost the moment the dialog closes.
    // -------------------------------------------------------------------------

    /**
     * The whole point of the GET: what it returns must be indistinguishable from what the one-shot
     * reconcile returned, field for field — otherwise a reprinted Z-read is a different document
     * from the one the cashier signed off.
     */
    @Test
    void zRead_returnsExactlyWhatReconcileReturned() {
        PosSession session = closedSession(1L, BigDecimal.ZERO,
                Instant.parse("2026-06-12T22:00:00Z"));

        when(sessions.findByUid("SZ1")).thenReturn(Optional.of(session));
        when(sessions.save(any())).thenReturn(session);
        when(invoices.sumGrossByPosSession(any())).thenReturn(new BigDecimal("800.00"));
        when(invoices.countByPosSession(any())).thenReturn(7L);
        when(tenderPayments.sumCashTenderByPosSession(any())).thenReturn(new BigDecimal("500.00"));
        when(tenderPayments.sumByPosSessionGroupedByTender(any()))
                .thenReturn(List.of(
                        new TenderSubtotalDto(TenderType.CASH, new BigDecimal("500.00")),
                        new TenderSubtotalDto(TenderType.MOBILE_MONEY, new BigDecimal("300.00"))));
        when(payouts.totalPayoutsForSession(any())).thenReturn(new BigDecimal("200.00"));
        when(payouts.payoutBreakdownForSession(any()))
                .thenReturn(List.of(
                        new PayoutSubtotalDto(PosPayoutType.REFUND, new BigDecimal("120.00"), 2L),
                        new PayoutSubtotalDto(PosPayoutType.PAID_OUT, new BigDecimal("80.00"), 1L)));

        ZReadDto atReconcile = service.reconcileSession("SZ1", new ReconcileSessionRequest(null));

        // The session is now RECONCILED — exactly the state a cashier hits "reprint" in.
        assertThat(session.getStatus()).isEqualTo(PosSessionStatus.RECONCILED);
        ZReadDto reRead = service.zRead("SZ1");

        assertThat(reRead)
                .as("a re-read Z-read must equal the one reconcile returned, field for field")
                .isEqualTo(atReconcile);
    }

    @Test
    void zRead_notYetReconciled_isRejectedWithoutLeakingInternals() {
        PosSession session = closedSession(1L, BigDecimal.ZERO,
                Instant.parse("2026-06-12T22:00:00Z"));

        when(sessions.findByUid("SZ2")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.zRead("SZ2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("has not been reconciled")
                // error-hygiene: friendly, actionable, zero internal detail
                .hasMessageNotContainingAny("Exception", "PosSession", "null", "SQL");
    }

    @Test
    void zRead_openSession_isRejected() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));

        when(sessions.findByUid("SZ3")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.zRead("SZ3"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void zRead_unknownSession_isNotFound() {
        when(sessions.findByUid("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.zRead("nope"))
                .isInstanceOf(NotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // X-read stays valid after the shift ends (it is a read, not a state change)
    // -------------------------------------------------------------------------

    @Test
    void xRead_closedSession_isAllowed() {
        PosSession session = closedSession(1L, BigDecimal.ZERO,
                Instant.parse("2026-06-12T22:00:00Z"));

        when(sessions.findByUid("SX1")).thenReturn(Optional.of(session));
        when(invoices.sumGrossByPosSession(any())).thenReturn(new BigDecimal("800.00"));
        when(invoices.countByPosSession(any())).thenReturn(3L);
        when(tenderPayments.sumCashTenderByPosSession(any())).thenReturn(new BigDecimal("500.00"));
        when(tenderPayments.sumByPosSessionGroupedByTender(any())).thenReturn(List.of());
        when(payouts.totalPayoutsForSession(any())).thenReturn(BigDecimal.ZERO);
        when(payouts.payoutBreakdownForSession(any())).thenReturn(List.of());

        var xRead = service.xRead("SX1");

        assertThat(xRead.totalSalesAmount()).isEqualByComparingTo(new BigDecimal("800.00"));
        // opening(500) + cash tender(500) − payouts(0)
        assertThat(xRead.expectedCashAmount()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(xRead.payoutSubtotals()).hasSameSizeAs(PosPayoutType.values());
    }

    /** Z is the authoritative end-of-shift report; X must not offer a second, competing summary. */
    @Test
    void xRead_reconciledSession_pointsAtTheZRead() {
        PosSession session = closedSession(1L, BigDecimal.ZERO,
                Instant.parse("2026-06-12T22:00:00Z"));
        session.setStatus(PosSessionStatus.RECONCILED);

        when(sessions.findByUid("SX2")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.xRead("SX2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Z-read");
    }

    // -------------------------------------------------------------------------
    // Payout breakdown: refunds vs drawer drops as separate lines (K1 print, K8 reporting)
    // -------------------------------------------------------------------------

    @Test
    void payoutBreakdown_sumsToTheMergedPayoutTotal() {
        PosSession session = reconciledSession(1L);

        when(sessions.findByUid("SP1")).thenReturn(Optional.of(session));
        when(invoices.sumGrossByPosSession(any())).thenReturn(new BigDecimal("800.00"));
        when(invoices.countByPosSession(any())).thenReturn(4L);
        when(tenderPayments.sumCashTenderByPosSession(any())).thenReturn(new BigDecimal("500.00"));
        when(tenderPayments.sumByPosSessionGroupedByTender(any())).thenReturn(List.of());
        when(payouts.totalPayoutsForSession(any())).thenReturn(new BigDecimal("200.00"));
        when(payouts.payoutBreakdownForSession(any()))
                .thenReturn(List.of(
                        new PayoutSubtotalDto(PosPayoutType.REFUND, new BigDecimal("120.00"), 2L),
                        new PayoutSubtotalDto(PosPayoutType.PAID_OUT, new BigDecimal("80.00"), 1L)));

        var zRead = service.zRead("SP1");

        BigDecimal breakdownSum = zRead.payoutSubtotals().stream()
                .map(PayoutSubtotalDto::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(breakdownSum)
                .as("the per-type breakdown must reconcile to the merged Payouts figure — "
                        + "no payout dropped, none double-counted")
                .isEqualByComparingTo(zRead.totalPayoutsNetAmount());
        assertThat(breakdownSum).isEqualByComparingTo(new BigDecimal("200.00"));
    }

    /**
     * A report whose rows appear and disappear between shifts is unreadable, so every payout type
     * is always present — zero-filled, in enum order — even when the session recorded none of it.
     */
    @Test
    void payoutBreakdown_zeroFillsTypesWithNoPayouts() {
        PosSession session = reconciledSession(1L);

        when(sessions.findByUid("SP2")).thenReturn(Optional.of(session));
        when(invoices.sumGrossByPosSession(any())).thenReturn(BigDecimal.ZERO);
        when(invoices.countByPosSession(any())).thenReturn(0L);
        when(tenderPayments.sumCashTenderByPosSession(any())).thenReturn(BigDecimal.ZERO);
        when(tenderPayments.sumByPosSessionGroupedByTender(any())).thenReturn(List.of());
        when(payouts.totalPayoutsForSession(any())).thenReturn(new BigDecimal("120.00"));
        // only REFUNDs were recorded — PAID_OUT never happened on this shift
        when(payouts.payoutBreakdownForSession(any()))
                .thenReturn(List.of(
                        new PayoutSubtotalDto(PosPayoutType.REFUND, new BigDecimal("120.00"), 2L)));

        var zRead = service.zRead("SP2");

        assertThat(zRead.payoutSubtotals())
                .extracting(PayoutSubtotalDto::payoutType)
                .containsExactly(PosPayoutType.values());
        assertThat(zRead.payoutSubtotals())
                .filteredOn(row -> row.payoutType() == PosPayoutType.PAID_OUT)
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.amount()).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(row.count()).isZero();
                });
        assertThat(zRead.payoutSubtotals())
                .filteredOn(row -> row.payoutType() == PosPayoutType.REFUND)
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.amount()).isEqualByComparingTo(new BigDecimal("120.00"));
                    assertThat(row.count()).isEqualTo(2L);
                });
    }

    @Test
    void payoutBreakdown_noPayoutsAtAll_isAllZeroesAndStillSumsToTotal() {
        PosSession session = reconciledSession(1L);

        when(sessions.findByUid("SP3")).thenReturn(Optional.of(session));
        when(invoices.sumGrossByPosSession(any())).thenReturn(BigDecimal.ZERO);
        when(invoices.countByPosSession(any())).thenReturn(0L);
        when(tenderPayments.sumCashTenderByPosSession(any())).thenReturn(BigDecimal.ZERO);
        when(tenderPayments.sumByPosSessionGroupedByTender(any())).thenReturn(List.of());
        when(payouts.totalPayoutsForSession(any())).thenReturn(BigDecimal.ZERO);
        when(payouts.payoutBreakdownForSession(any())).thenReturn(List.of());

        var zRead = service.zRead("SP3");

        assertThat(zRead.payoutSubtotals()).hasSameSizeAs(PosPayoutType.values());
        assertThat(zRead.payoutSubtotals())
                .allSatisfy(row -> assertThat(row.amount()).isEqualByComparingTo(BigDecimal.ZERO));
        assertThat(zRead.totalPayoutsNetAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // -------------------------------------------------------------------------
    // K8 — till expenses: a payout must reach the ledger, not just a row
    // -------------------------------------------------------------------------

    /**
     * The live accounting defect. A payout wrote a row and an audit event and nothing else, and
     * because expected cash already nets payouts the reconcile posting saw a zero variance and never
     * caught it either — GL cash overstated, P&amp;L understated, by every till payout ever taken.
     */
    @Test
    void recordPayout_paidOut_postsDrExpenseCrCash() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("K8-1")).thenReturn(Optional.of(session));
        when(payouts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubPayoutGlAccounts();

        var dto = service.recordPayout("K8-1",
                new PosPayoutRequest(PosPayoutType.PAID_OUT, new BigDecimal("50000.00"),
                        "Water delivery for the shop"));

        ArgumentCaptor<JournalEntryDraft> draft = ArgumentCaptor.forClass(JournalEntryDraft.class);
        verify(glPosting).post(draft.capture());
        var lines = draft.getValue().lines();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).accountId()).as("DR the expense account").isEqualTo(EXPENSE_ACCT_ID);
        assertThat(lines.get(0).debitAmount()).isEqualByComparingTo("50000.00");
        assertThat(lines.get(1).accountId()).as("CR cash").isEqualTo(CASH_ACCT_ID);
        assertThat(lines.get(1).creditAmount()).isEqualByComparingTo("50000.00");
        assertThat(dto.journalEntryUid()).isEqualTo("JRN-1");
    }

    /**
     * The journal must be traceable back to the exact payout, and must not masquerade as the
     * reconcile entry — that separation is how an accountant tells a real till expense from a
     * genuine cash shortage while the category/account columns are still missing.
     */
    @Test
    void recordPayout_journalIsTiedToThePayoutAndNotToTheVarianceSource() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("K8-2")).thenReturn(Optional.of(session));
        when(payouts.save(any()))
                .thenAnswer(inv -> withUid(inv.getArgument(0), "PAYOUT-0000000000000000001"));
        stubPayoutGlAccounts();

        var dto = service.recordPayout("K8-2",
                new PosPayoutRequest(PosPayoutType.PAID_OUT, new BigDecimal("2000.00"),
                        "Transport for stock collection"));

        ArgumentCaptor<JournalEntryDraft> draft = ArgumentCaptor.forClass(JournalEntryDraft.class);
        verify(glPosting).post(draft.capture());
        assertThat(dto.uid()).isEqualTo("PAYOUT-0000000000000000001");
        assertThat(draft.getValue().sourceRef())
                .as("the journal must point back at the exact payout it accounts for")
                .isEqualTo(dto.uid());
        assertThat(draft.getValue().sourceType())
                .as("a till expense must be separable from the reconcile variance entry")
                .isEqualTo(JournalSourceType.CASH_DIRECT)
                .isNotEqualTo(JournalSourceType.POS_VARIANCE);
    }

    /**
     * A refund's cash leg is already reversed by the invoice void ({@code reverseSale}); posting it
     * here as well would credit cash twice for one refund. Refund accounting stays with the
     * sale-void / credit-note path.
     */
    @Test
    void recordPayout_refund_raisesNoJournal_soCashIsNotCreditedTwice() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("K8-3")).thenReturn(Optional.of(session));
        when(payouts.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.recordPayout("K8-3",
                new PosPayoutRequest(PosPayoutType.REFUND, new BigDecimal("3000.00"),
                        "Customer returned a faulty kettle"));

        verify(glPosting, never()).post(any());
        assertThat(dto.journalEntryUid()).isNull();
    }

    /**
     * Cash that cannot be accounted for must not be recorded as if it had been: the posting failing
     * takes the payout row down with it, and the cashier gets a message with no internals in it.
     */
    @Test
    void recordPayout_whenGlIsNotConfigured_refusesThePayoutWithoutLeakingInternals() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("K8-4")).thenReturn(Optional.of(session));
        when(payouts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(glConfig.resolve(anyLong(), any()))
                .thenThrow(new IllegalStateException(
                        "No GL account is mapped for the posting role 'POS_TILL_EXPENSE'."));
        var req = new PosPayoutRequest(PosPayoutType.PAID_OUT, new BigDecimal("500.00"),
                "Casual labour");

        assertThatThrownBy(() -> service.recordPayout("K8-4", req))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(ex.getMessage())
                        .doesNotContain("POS_TILL_EXPENSE")
                        .doesNotContain("Exception")
                        .doesNotContain("null"));
    }

    /** An expense with no stated purpose is not an expense anyone can account for. */
    @Test
    void recordPayout_blankReason_isRefused() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("K8-5")).thenReturn(Optional.of(session));
        var req = new PosPayoutRequest(PosPayoutType.PAID_OUT, new BigDecimal("500.00"), "   ");

        assertThatThrownBy(() -> service.recordPayout("K8-5", req))
                .isInstanceOf(ConflictException.class);
        verify(payouts, never()).save(any());
        verify(glPosting, never()).post(any());
    }

    /**
     * The payout posting and the reconcile posting must not both book the same cash. Expected cash
     * already nets payouts, so a correctly-recorded payout leaves the variance at zero and reconcile
     * posts nothing — the two are orthogonal by construction.
     */
    @Test
    void payoutPosting_doesNotDoubleCountAgainstTheReconcilePosting() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("K8-6")).thenReturn(Optional.of(session));
        when(tenderPayments.sumCashTenderByPosSession(session.getId()))
                .thenReturn(new BigDecimal("500.00"));
        when(payouts.totalPayoutsForSession(session.getId())).thenReturn(new BigDecimal("200.00"));
        when(sessions.save(any())).thenReturn(session);

        // Counted cash agrees with the drawer after the 200 payout: 1000 + 500 - 200 = 1300.
        service.closeSession("K8-6", new CloseSessionRequest(new BigDecimal("1300.00"), null));
        assertThat(session.getVarianceAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        service.reconcileSession("K8-6", new ReconcileSessionRequest(null));
        verify(glInvoker, never()).postInNewTx(any());
    }

    /** The detail behind the merged "Payouts" line — previously only a SUM existed. */
    @Test
    void listPayouts_returnsTheIndividualRowsOldestFirst() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("K8-7")).thenReturn(Optional.of(session));
        when(payouts.findByPosSessionIdOrderByCreatedAtAscIdAsc(session.getId()))
                .thenReturn(List.of(
                        payoutRow(PosPayoutType.PAID_OUT, new BigDecimal("500.00"), "Water"),
                        payoutRow(PosPayoutType.REFUND, new BigDecimal("300.00"), "Faulty kettle")));

        var rows = service.listPayouts("K8-7");

        assertThat(rows).extracting(PosPayoutDto::reason)
                .containsExactly("Water", "Faulty kettle");
        assertThat(rows).extracting(PosPayoutDto::amount)
                .containsExactly(new BigDecimal("500.00"), new BigDecimal("300.00"));
    }

    /**
     * The journal reference is now a persisted column (V94), not something known only to the response
     * that created the payout — so a listed payout can be traced to its GL entry days later.
     */
    @Test
    void listPayouts_readsBackThePersistedJournalAndAccount() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        PosSessionPayout posted = payoutRow(PosPayoutType.PAID_OUT, new BigDecimal("500.00"), "Water");
        posted.markPostedToGl(EXPENSE_ACCT_ID, "JRN-PERSISTED");
        when(sessions.findByUid("K8-8")).thenReturn(Optional.of(session));
        when(payouts.findByPosSessionIdOrderByCreatedAtAscIdAsc(session.getId()))
                .thenReturn(List.of(posted));

        var rows = service.listPayouts("K8-8");

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.journalEntryUid()).isEqualTo("JRN-PERSISTED");
            assertThat(row.glAccountId()).isEqualTo(EXPENSE_ACCT_ID);
        });
    }

    // -------------------------------------------------------------------------
    // K8/V94 — a till EXPENSE is its own thing: categorised, posted, reportable
    // -------------------------------------------------------------------------

    /**
     * The whole point of the EXPENSE type. Cash for transport or water is a business expense, and
     * until V94 it could only be filed as an untyped PAID_OUT with the reason as free text — so the
     * shop could not say what it had spent. The row now carries the bucket, the account it hit and
     * the journal it raised.
     */
    @Test
    void recordExpense_persistsCategoryAccountAndJournal() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("K8-E1")).thenReturn(Optional.of(session));
        when(payouts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubPayoutGlAccounts();

        var dto = service.recordExpense("K8-E1",
                new PosExpenseRequest(new BigDecimal("4500.00"), "Transport",
                        "Boda to collect stock from the wholesaler"));

        assertThat(dto.payoutType()).isEqualTo(PosPayoutType.EXPENSE);
        assertThat(dto.category()).isEqualTo("Transport");
        assertThat(dto.glAccountId())
                .as("the resolved expense account is recorded on the row so it can be reclassified")
                .isEqualTo(EXPENSE_ACCT_ID);
        assertThat(dto.journalEntryUid()).isEqualTo("JRN-1");
    }

    /** An expense moves cash out of the business exactly as a paid-out does, so it posts the same. */
    @Test
    void recordExpense_postsDrExpenseCrCash() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("K8-E2")).thenReturn(Optional.of(session));
        when(payouts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubPayoutGlAccounts();

        service.recordExpense("K8-E2",
                new PosExpenseRequest(new BigDecimal("12000.00"), "Water",
                        "Drinking water for the shop"));

        ArgumentCaptor<JournalEntryDraft> draft = ArgumentCaptor.forClass(JournalEntryDraft.class);
        verify(glPosting).post(draft.capture());
        var lines = draft.getValue().lines();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).accountId()).as("DR the expense account").isEqualTo(EXPENSE_ACCT_ID);
        assertThat(lines.get(0).debitAmount()).isEqualByComparingTo("12000.00");
        assertThat(lines.get(0).lineMemo())
                .as("the memo names the bucket, so the journal is readable without the payout row")
                .contains("Water");
        assertThat(lines.get(1).accountId()).as("CR cash").isEqualTo(CASH_ACCT_ID);
        assertThat(lines.get(1).creditAmount()).isEqualByComparingTo("12000.00");
        assertThat(draft.getValue().sourceType())
                .as("a till expense stays separable from the reconcile variance entry")
                .isEqualTo(JournalSourceType.CASH_DIRECT);
    }

    /**
     * The reason V94 added {@code journal_entry_uid} at all: a payout that already carries a journal
     * is never posted a second time, so no retry can credit cash twice for the same drawer cash.
     */
    @Test
    void postingIsIdempotent_anAlreadyPostedPayoutRaisesNoSecondJournal() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("K8-E3")).thenReturn(Optional.of(session));
        // The row comes back already carrying a journal — the state a replayed posting would meet.
        when(payouts.save(any())).thenAnswer(inv -> {
            PosSessionPayout p = inv.getArgument(0);
            p.markPostedToGl(EXPENSE_ACCT_ID, "JRN-ALREADY-POSTED");
            return p;
        });
        stubPayoutGlAccounts();

        var dto = service.recordExpense("K8-E3",
                new PosExpenseRequest(new BigDecimal("800.00"), "Parking", "Parking fee"));

        verify(glPosting, never()).post(any());
        assertThat(dto.journalEntryUid())
                .as("the existing journal is returned untouched, never replaced by a second one")
                .isEqualTo("JRN-ALREADY-POSTED");
    }

    /**
     * An expense is a separately-permissioned capability with a mandatory category. Letting it
     * through the generic payout form would let anyone who can merely open a session book an
     * uncategorised "expense" the expense gate never checked.
     */
    @Test
    void recordPayout_refusesAnExpense_soItCannotBypassTheExpenseGate() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("K8-E4")).thenReturn(Optional.of(session));
        var req = new PosPayoutRequest(PosPayoutType.EXPENSE, new BigDecimal("500.00"),
                "Transport money");

        assertThatThrownBy(() -> service.recordPayout("K8-E4", req))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(ex.getMessage())
                        .doesNotContain("Exception")
                        .doesNotContain("null"));
        verify(payouts, never()).save(any());
        verify(glPosting, never()).post(any());
    }

    /** A blank category would produce a report bucket nobody can act on. */
    @Test
    void recordExpense_blankCategory_isRefused() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("K8-E5")).thenReturn(Optional.of(session));
        var req = new PosExpenseRequest(new BigDecimal("500.00"), "   ", "Casual labour");

        assertThatThrownBy(() -> service.recordExpense("K8-E5", req))
                .isInstanceOf(ConflictException.class);
        verify(payouts, never()).save(any());
        verify(glPosting, never()).post(any());
    }

    /**
     * {@code category} is VARCHAR(40) (V94). Refuse rather than truncate: silently cutting the
     * operator's words is how a report stops meaning what it says — and how the insert would fail
     * at the database instead of at the user.
     */
    @Test
    void recordExpense_overlongCategory_isRefusedRatherThanTruncated() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("K8-E6")).thenReturn(Optional.of(session));
        var req = new PosExpenseRequest(new BigDecimal("500.00"), "x".repeat(41), "Casual labour");

        assertThatThrownBy(() -> service.recordExpense("K8-E6", req))
                .isInstanceOf(ConflictException.class);
        verify(payouts, never()).save(any());
    }

    /** The reason stays mandatory on an expense, exactly as on a payout. */
    @Test
    void recordExpense_blankReason_isRefused() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("K8-E7")).thenReturn(Optional.of(session));
        var req = new PosExpenseRequest(new BigDecimal("500.00"), "Transport", "  ");

        assertThatThrownBy(() -> service.recordExpense("K8-E7", req))
                .isInstanceOf(ConflictException.class);
        verify(payouts, never()).save(any());
        verify(glPosting, never()).post(any());
    }

    /** Cash that cannot be accounted for must not be recorded as if it had been — same as a payout. */
    @Test
    void recordExpense_whenGlIsNotConfigured_refusesTheExpenseWithoutLeakingInternals() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("K8-E8")).thenReturn(Optional.of(session));
        when(payouts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(glConfig.resolve(anyLong(), any()))
                .thenThrow(new IllegalStateException(
                        "No GL account is mapped for the posting role 'POS_TILL_EXPENSE'."));
        var req = new PosExpenseRequest(new BigDecimal("500.00"), "Transport", "Boda fare");

        assertThatThrownBy(() -> service.recordExpense("K8-E8", req))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(ex.getMessage())
                        .doesNotContain("POS_TILL_EXPENSE")
                        .doesNotContain("Exception")
                        .doesNotContain("null"));
    }

    // -------------------------------------------------------------------------
    // V97 — a till expense is an EXPENSE, not a drawer SHORTAGE
    // -------------------------------------------------------------------------

    /**
     * The misstatement V97 closes. Every till expense used to be debited to the account mapped to
     * {@code POS_CASH_SHORT} — "Cash Short / Till Shortage". That is a VARIANCE account whose whole
     * purpose is detecting drawer discrepancies, so routine spend poured into it destroys the control
     * signal (a genuine shortage becomes indistinguishable from a bus fare) AND misclassifies
     * operating expense by nature on the P&amp;L.
     *
     * <p>Both accounts are stubbed, to different ids, so a regression that reaches for the shortage
     * account again fails on the id rather than on a missing stub.
     */
    @Test
    void recordExpense_debitsTheTillExpenseAccount_neverTheCashShortageAccount() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("V97-1")).thenReturn(Optional.of(session));
        when(payouts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubPayoutGlAccounts();

        var dto = service.recordExpense("V97-1",
                new PosExpenseRequest(new BigDecimal("1234.00"), "Transport", "Boda fare"));

        ArgumentCaptor<JournalEntryDraft> draft = ArgumentCaptor.forClass(JournalEntryDraft.class);
        verify(glPosting).post(draft.capture());
        assertThat(draft.getValue().lines().get(0).accountId())
                .as("DR the till EXPENSE account (5175), not the till SHORTAGE account (5170)")
                .isEqualTo(EXPENSE_ACCT_ID)
                .isNotEqualTo(CASH_SHORT_ACCT_ID);
        assertThat(dto.glAccountId())
                .as("and the same account is stamped on the payout row (V94 gl_account_id)")
                .isEqualTo(EXPENSE_ACCT_ID);
        verify(glConfig, never()).resolve(anyLong(), eq(GlConfigKey.POS_CASH_SHORT));
    }

    /**
     * Self-healing provisioning: the mapping is ensured on the posting path as a backstop to the
     * seeder's start-up pass, so a company created between two boots gets it on the first expense —
     * no manual setup step and nothing seeded in SQL (standing rule: provisioning over data
     * migrations). {@code ensureMapping}, not {@code seedDefaults}: the mapping must be committed in
     * its own transaction so it outlives an expense that is refused for some other reason.
     */
    @Test
    void recordExpense_provisionsTheTillExpenseMappingOnDemand() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("V97-2")).thenReturn(Optional.of(session));
        when(payouts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubPayoutGlAccounts();

        service.recordExpense("V97-2",
                new PosExpenseRequest(new BigDecimal("500.00"), "Water", "Drinking water"));

        verify(tillExpenseGl).ensureMapping(1L);
        verify(tillExpenseGl, never()).seedDefaults(anyLong());
    }

    /**
     * A seeder that cannot provision must not surface as a raw failure. {@code ensureMapping} is
     * documented never to throw, but the posting path is what a cashier actually meets, so it is
     * guarded here too: the refusal a till shows is the actionable "set the till expense account"
     * sentence, never internal detail.
     */
    @Test
    void recordExpense_whenProvisioningItselfFails_stillRefusesWithTheFriendlyMessage() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("V97-3")).thenReturn(Optional.of(session));
        when(payouts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new IllegalStateException("chart_of_accounts insert exploded"))
                .when(tillExpenseGl).ensureMapping(1L);

        assertThatThrownBy(() -> service.recordExpense("V97-3",
                new PosExpenseRequest(new BigDecimal("500.00"), "Water", "Drinking water")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("till expense account")
                .hasMessageNotContaining("chart_of_accounts");
        verify(glPosting, never()).post(any());
    }

    /**
     * The fallback that must NOT exist. When the expense mapping cannot be resolved even after
     * provisioning, the expense is refused with an actionable sentence — it is never quietly posted
     * to the shortage account. A refusal the finance team can act on beats a silent misposting
     * nobody will ever notice.
     */
    @Test
    void recordExpense_whenTheExpenseAccountIsUnmapped_refusesRatherThanFallingBackToCashShort() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("V97-3")).thenReturn(Optional.of(session));
        when(payouts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ChartOfAccount cashShort = mock(ChartOfAccount.class);
        when(glConfig.resolve(anyLong(), eq(GlConfigKey.POS_CASH_SHORT))).thenReturn(cashShort);
        when(glConfig.resolve(anyLong(), eq(GlConfigKey.POS_TILL_EXPENSE)))
                .thenThrow(new IllegalStateException(
                        "No GL account is mapped for the posting role 'POS_TILL_EXPENSE'."));
        var req = new PosExpenseRequest(new BigDecimal("500.00"), "Transport", "Boda fare");

        assertThatThrownBy(() -> service.recordExpense("V97-3", req))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(ex.getMessage())
                        .as("says what to fix, in words a cashier can repeat to the accountant")
                        .contains("till expense")
                        .doesNotContain("POS_TILL_EXPENSE")
                        .doesNotContain("Exception"));
        verify(glPosting, never()).post(any());
    }

    // -------------------------------------------------------------------------
    // V98 — Idempotency-Key: one expense entry, one payout, one journal
    // -------------------------------------------------------------------------

    /**
     * The live defect. Two byte-identical POSTs wrote TWO payouts and TWO journals — 2,468 posted
     * for a single 1,234 expense (payout rows 17 and 18). Under the same key the second call now
     * REPLAYS the first payout: no second row, no second journal, and the caller gets the original
     * back rather than a refusal it did nothing to earn.
     */
    @Test
    void recordExpense_sameKeyTwice_replaysTheOriginalPayoutAndWritesNothingNew() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("V98-1")).thenReturn(Optional.of(session));
        when(payouts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubPayoutGlAccounts();
        // Mocks are built BEFORE the when() calls: creating a mock inside an in-progress stubbing
        // is what Mockito reports as UnfinishedStubbing.
        PosExpenseIdempotency stamped = marker("PAYOUT-0000000000000000001");
        PosSessionPayout original = expenseRow(new BigDecimal("1234.00"), "Transport", "Boda fare",
                Instant.now());
        // First call claims the key; the second finds it already stamped with the winner's payout.
        when(expenseIdempotency.findByCompanyIdAndIdemKey(1L, "K-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(stamped));
        when(expenseIdempotency.tryReserve(eq(1L), any(), eq("K-1"))).thenReturn(1);
        when(payouts.findByUid("PAYOUT-0000000000000000001")).thenReturn(Optional.of(original));
        var req = new PosExpenseRequest(new BigDecimal("1234.00"), "Transport", "Boda fare");

        var first  = service.recordExpense("V98-1", "K-1", req);
        var second = service.recordExpense("V98-1", "K-1", req);

        assertThat(second.amount())
                .as("the ORIGINAL payout comes back")
                .isEqualByComparingTo(first.amount());
        verify(payouts, times(1)).save(any());
        verify(glPosting, times(1)).post(any());
        verify(expenseIdempotency).stampPayoutUid(1L, "K-1", first.uid());
    }

    /**
     * The stamp happens in the SAME transaction as the payout (it is one {@code @Transactional}
     * service call) and only AFTER the payout exists — a marker stamped with a uid that later rolls
     * back would replay a payout nobody can find.
     */
    @Test
    void recordExpense_stampsTheMarkerWithThePayoutThatWasActuallyWritten() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("V98-2")).thenReturn(Optional.of(session));
        when(payouts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubPayoutGlAccounts();
        when(expenseIdempotency.findByCompanyIdAndIdemKey(1L, "K-2")).thenReturn(Optional.empty());
        when(expenseIdempotency.tryReserve(eq(1L), any(), eq("K-2"))).thenReturn(1);

        var dto = service.recordExpense("V98-2", "K-2",
                new PosExpenseRequest(new BigDecimal("900.00"), "Water", "Drinking water"));

        verify(expenseIdempotency).stampPayoutUid(1L, "K-2", dto.uid());
    }

    /**
     * The loser of a genuinely simultaneous pair — the case the window guard could never close, since
     * two transactions can both read before either commits. Its insert blocks on the winner and comes
     * back 0; while the winner is still mid-transaction the marker carries no payout yet, so the
     * caller is told to wait rather than shown a payout that may still roll back — and is certainly
     * not given a second one.
     */
    @Test
    void recordExpense_concurrentDuplicate_isToldToWaitRatherThanWritingASecondPayout() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("V98-3")).thenReturn(Optional.of(session));
        PosExpenseIdempotency unstamped = marker(null); // reserved by the winner, not yet stamped
        when(expenseIdempotency.findByCompanyIdAndIdemKey(1L, "K-3"))
                .thenReturn(Optional.empty())           // not claimed when first read
                .thenReturn(Optional.of(unstamped));
        when(expenseIdempotency.tryReserve(eq(1L), any(), eq("K-3"))).thenReturn(0);
        var req = new PosExpenseRequest(new BigDecimal("1234.00"), "Transport", "Boda fare");

        assertThatThrownBy(() -> service.recordExpense("V98-3", "K-3", req))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(ex.getMessage())
                        .doesNotContain("Exception")
                        .doesNotContain("null"));
        verify(payouts, never()).save(any());
        verify(glPosting, never()).post(any());
    }

    /**
     * Two DIFFERENT keys are two payments the client deliberately declared as separate — two 2,000
     * fares in one shift is an ordinary day in a shop. Both are recorded, and the interim window
     * guard (which matches on amount + category + note and would refuse the second) does NOT run:
     * the key wins wherever both mechanisms could speak.
     */
    @Test
    void recordExpense_differentKeys_recordBothPaymentsEvenWhenIdenticalInEveryOtherRespect() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("V98-4")).thenReturn(Optional.of(session));
        when(payouts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubPayoutGlAccounts();
        when(expenseIdempotency.findByCompanyIdAndIdemKey(eq(1L), any()))
                .thenReturn(Optional.empty());
        when(expenseIdempotency.tryReserve(eq(1L), any(), any())).thenReturn(1);
        // A matching expense a second ago — exactly what the window guard refuses a keyless caller.
        PosSessionPayout earlier = expenseRow(new BigDecimal("2000.00"), "Transport", "Boda fare",
                Instant.now().minusSeconds(1));
        when(payouts.findByPosSessionIdOrderByCreatedAtAscIdAsc(any()))
                .thenReturn(List.of(earlier));
        var req = new PosExpenseRequest(new BigDecimal("2000.00"), "Transport", "Boda fare");

        service.recordExpense("V98-4", "K-4a", req);
        service.recordExpense("V98-4", "K-4b", req);

        verify(payouts, times(2)).save(any());
        verify(glPosting, times(2)).post(any());
    }

    /**
     * A keyless caller (an older POS build) keeps the interim window guard — it is the only
     * protection such a client has against the double-tap that produced rows 17 and 18.
     */
    @Test
    void recordExpense_withoutAKey_stillRefusesAnIdenticalRepeatInsideTheWindow() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("V98-5")).thenReturn(Optional.of(session));
        PosSessionPayout earlier = expenseRow(new BigDecimal("1234.00"), "Transport", "Boda fare",
                Instant.now().minusSeconds(1));
        when(payouts.findByPosSessionIdOrderByCreatedAtAscIdAsc(any()))
                .thenReturn(List.of(earlier));
        var req = new PosExpenseRequest(new BigDecimal("1234.00"), "Transport", "Boda fare");

        assertThatThrownBy(() -> service.recordExpense("V98-5", req))
                .isInstanceOf(ConflictException.class);
        verify(payouts, never()).save(any());
        verify(expenseIdempotency, never()).tryReserve(any(), any(), any());
    }

    /**
     * A stamped marker is a definitive "yes, this posted" and is answered whatever the till has done
     * since. Refusing a replay because the shift has closed is precisely the loop that makes a client
     * retry forever — and each retry used to write another payout.
     */
    @Test
    void recordExpense_replayIsAnsweredEvenAfterTheSessionClosed() {
        PosSession session = closedSession(1L, BigDecimal.ZERO, Instant.now());
        when(sessions.findByUid("V98-6")).thenReturn(Optional.of(session));
        PosExpenseIdempotency stamped = marker("PAYOUT-REPLAY");
        PosSessionPayout original = expenseRow(new BigDecimal("1234.00"), "Transport", "Boda fare",
                Instant.now());
        when(expenseIdempotency.findByCompanyIdAndIdemKey(1L, "K-6"))
                .thenReturn(Optional.of(stamped));
        when(payouts.findByUid("PAYOUT-REPLAY")).thenReturn(Optional.of(original));

        var dto = service.recordExpense("V98-6", "K-6",
                new PosExpenseRequest(new BigDecimal("1234.00"), "Transport", "Boda fare"));

        assertThat(dto.amount()).isEqualByComparingTo("1234.00");
        verify(payouts, never()).save(any());
        verify(glPosting, never()).post(any());
    }

    /** {@code idem_key} is VARCHAR(80) (V98) — refuse in words, not as a database error. */
    @Test
    void recordExpense_overlongKey_isRefusedWithoutTouchingTheDrawer() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("V98-7")).thenReturn(Optional.of(session));
        var req = new PosExpenseRequest(new BigDecimal("500.00"), "Transport", "Boda fare");

        assertThatThrownBy(() -> service.recordExpense("V98-7", "k".repeat(81), req))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(ex.getMessage())
                        .doesNotContain("Exception")
                        .doesNotContain("VARCHAR"));
        verify(expenseIdempotency, never()).tryReserve(any(), any(), any());
        verify(payouts, never()).save(any());
    }

    /** A blank header is "no key" — the previous behaviour, guard and all. */
    @Test
    void recordExpense_blankKey_behavesExactlyAsNoKey() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("V98-8")).thenReturn(Optional.of(session));
        when(payouts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubPayoutGlAccounts();

        service.recordExpense("V98-8", "   ",
                new PosExpenseRequest(new BigDecimal("500.00"), "Water", "Drinking water"));

        verify(expenseIdempotency, never()).tryReserve(any(), any(), any());
        verify(expenseIdempotency, never()).stampPayoutUid(any(), any(), any());
        verify(payouts).save(any());
    }

    // -------------------------------------------------------------------------
    // K8 — EXPENSE prints as its own X/Z-read line
    // -------------------------------------------------------------------------

    /**
     * The printed-report defect this closes: a Z that lumps a refund together with a paid-out expense
     * tells the owner nothing about what the shop spent. Each type gets its own line, and they still
     * reconcile to the merged Payouts figure.
     */
    @Test
    void zReadBreakdown_printsExpenseAsItsOwnLine_notMixedIntoRefundsOrDrops() {
        PosSession session = reconciledSession(1L);

        when(sessions.findByUid("K8-E9")).thenReturn(Optional.of(session));
        when(invoices.sumGrossByPosSession(any())).thenReturn(new BigDecimal("800.00"));
        when(invoices.countByPosSession(any())).thenReturn(4L);
        when(tenderPayments.sumCashTenderByPosSession(any())).thenReturn(new BigDecimal("500.00"));
        when(tenderPayments.sumByPosSessionGroupedByTender(any())).thenReturn(List.of());
        when(payouts.totalPayoutsForSession(any())).thenReturn(new BigDecimal("260.00"));
        when(payouts.payoutBreakdownForSession(any()))
                .thenReturn(List.of(
                        new PayoutSubtotalDto(PosPayoutType.REFUND,   new BigDecimal("120.00"), 2L),
                        new PayoutSubtotalDto(PosPayoutType.PAID_OUT, new BigDecimal("80.00"),  1L),
                        new PayoutSubtotalDto(PosPayoutType.EXPENSE,  new BigDecimal("60.00"),  3L)));

        var zRead = service.zRead("K8-E9");

        assertThat(zRead.payoutSubtotals())
                .extracting(PayoutSubtotalDto::payoutType)
                .containsExactly(PosPayoutType.values());
        assertThat(zRead.payoutSubtotals())
                .filteredOn(row -> row.payoutType() == PosPayoutType.EXPENSE)
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.amount()).isEqualByComparingTo(new BigDecimal("60.00"));
                    assertThat(row.count()).isEqualTo(3L);
                });
        BigDecimal breakdownSum = zRead.payoutSubtotals().stream()
                .map(PayoutSubtotalDto::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(breakdownSum)
                .as("three lines must still reconcile to the merged Payouts figure")
                .isEqualByComparingTo(zRead.totalPayoutsNetAmount());
    }

    /** A shift with no expenses still prints the EXPENSE line at zero, so the layout never moves. */
    @Test
    void xReadBreakdown_zeroFillsTheExpenseLineWhenNoneWereRecorded() {
        PosSession session = openSession(1L, new BigDecimal("500.00"));

        when(sessions.findByUid("K8-E10")).thenReturn(Optional.of(session));
        when(invoices.sumGrossByPosSession(any())).thenReturn(BigDecimal.ZERO);
        when(invoices.countByPosSession(any())).thenReturn(0L);
        when(tenderPayments.sumCashTenderByPosSession(any())).thenReturn(BigDecimal.ZERO);
        when(tenderPayments.sumByPosSessionGroupedByTender(any())).thenReturn(List.of());
        when(payouts.totalPayoutsForSession(any())).thenReturn(BigDecimal.ZERO);
        when(payouts.payoutBreakdownForSession(any())).thenReturn(List.of());

        var xRead = service.xRead("K8-E10");

        assertThat(xRead.payoutSubtotals())
                .filteredOn(row -> row.payoutType() == PosPayoutType.EXPENSE)
                .singleElement()
                .satisfies(row -> assertThat(row.amount()).isEqualByComparingTo(BigDecimal.ZERO));
    }

    // -------------------------------------------------------------------------
    // K8 — the cross-session till-expense report (POS.EXPENSE.VIEW)
    // -------------------------------------------------------------------------

    /**
     * The report the owner actually asked for: what the tills spent, bucketed, biggest first. The
     * roll-up is derived from the same rows the report lists, so the two can never disagree.
     */
    @Test
    void tillExpenseReport_rollsUpByCategoryBiggestFirstAndSumsToTheTotal() {
        when(payouts.findExpensesForCompanyBetween(eq(1L), eq(PosPayoutType.EXPENSE), any(), any()))
                .thenReturn(List.of(
                        expenseRow("Transport", new BigDecimal("3000.00")),
                        expenseRow("Water",     new BigDecimal("1000.00")),
                        expenseRow("Transport", new BigDecimal("2000.00"))));

        var report = service.tillExpenseReport(1L, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 8));

        assertThat(report.count()).isEqualTo(3L);
        assertThat(report.totalAmount()).isEqualByComparingTo(new BigDecimal("6000.00"));
        assertThat(report.byCategory()).extracting(TillExpenseCategoryTotalDto::category)
                .as("biggest spend first — the line worth questioning is at the top")
                .containsExactly("Transport", "Water");
        assertThat(report.byCategory().get(0).amount()).isEqualByComparingTo("5000.00");
        assertThat(report.byCategory().get(0).count()).isEqualTo(2L);
        BigDecimal rollUpSum = report.byCategory().stream()
                .map(TillExpenseCategoryTotalDto::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(rollUpSum)
                .as("the roll-up must neither drop nor double-count an expense")
                .isEqualByComparingTo(report.totalAmount());
    }

    /**
     * Only EXPENSE rows may reach this report — folding refunds and drawer drops into an "expense
     * report" is the exact confusion the feature exists to remove.
     */
    @Test
    void tillExpenseReport_queriesExpensesOnly() {
        when(payouts.findExpensesForCompanyBetween(anyLong(), any(), any(), any()))
                .thenReturn(List.of());

        service.tillExpenseReport(1L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 8));

        verify(payouts).findExpensesForCompanyBetween(eq(1L), eq(PosPayoutType.EXPENSE),
                any(), any());
    }

    /**
     * The upper bound is EXCLUSIVE and one day past the closing date, so an expense recorded late on
     * the last day is inside the report rather than silently missing from it.
     */
    @Test
    void tillExpenseReport_includesTheWholeOfTheClosingDay() {
        when(payouts.findExpensesForCompanyBetween(anyLong(), any(), any(), any()))
                .thenReturn(List.of());

        service.tillExpenseReport(1L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 8));

        ArgumentCaptor<Instant> from  = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> until = ArgumentCaptor.forClass(Instant.class);
        verify(payouts).findExpensesForCompanyBetween(anyLong(), any(),
                from.capture(), until.capture());
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(until.getValue()).isEqualTo(Instant.parse("2026-08-09T00:00:00Z"));
    }

    /** The report is company-scoped before a single row is read — no cross-tenant read. */
    @Test
    void tillExpenseReport_assertsCompanyScopeBeforeReading() {
        when(payouts.findExpensesForCompanyBetween(anyLong(), any(), any(), any()))
                .thenReturn(List.of());

        service.tillExpenseReport(7L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 8));

        verify(scopeGuard).assertCanActIn(any(), eq(7L));
    }

    /** A backwards date range is a mistake, not an empty report that quietly hides the mistake. */
    @Test
    void tillExpenseReport_endBeforeStart_isRefused() {
        assertThatThrownBy(() -> service.tillExpenseReport(1L,
                LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 1)))
                .isInstanceOf(ConflictException.class);
        verify(payouts, never()).findExpensesForCompanyBetween(anyLong(), any(), any(), any());
    }

    /** No dates named → a sane bounded window rather than every expense ever recorded. */
    @Test
    void tillExpenseReport_withoutDates_defaultsToABoundedWindow() {
        when(payouts.findExpensesForCompanyBetween(anyLong(), any(), any(), any()))
                .thenReturn(List.of());

        var report = service.tillExpenseReport(1L, null, null);

        assertThat(LocalDate.parse(report.fromDate()))
                .isEqualTo(LocalDate.parse(report.toDate()).minusDays(30));
        assertThat(report.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.byCategory()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // UAT C4 — one payment, one payout row: the repeated-expense window
    // -------------------------------------------------------------------------

    /**
     * The defect, exactly as it was observed live: two byte-identical POSTs to the expense endpoint
     * wrote two payout rows and two journals — 2,468 TZS in the ledger and in the expense report for
     * the 1,234 that actually left the drawer. The endpoint takes no idempotency key, so a
     * double-tapped button or a client retry after a lost response looked like two payments.
     */
    @Test
    void recordExpense_identicalRepeatWithinTheWindow_isRefusedAndNeitherRowNorJournalIsWritten() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("C4-1")).thenReturn(Optional.of(session));
        when(payouts.findByPosSessionIdOrderByCreatedAtAscIdAsc(any()))
                .thenReturn(List.of(expenseRow("TRANSPORT", new BigDecimal("1234.00"),
                        "Boda fare to the bank")));

        var req = new PosExpenseRequest(new BigDecimal("1234.00"), "TRANSPORT",
                "Boda fare to the bank");

        assertThatThrownBy(() -> service.recordExpense("C4-1", req))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(ex.getMessage())
                        .as("the cashier must be told the cash was already taken out, in plain words")
                        .contains("already recorded")
                        .doesNotContain("Exception")
                        .doesNotContain("idempotency"));
        verify(payouts, never()).save(any());
        verify(glPosting, never()).post(any());
    }

    /**
     * Same money, same bucket, same note — recorded as {@code 1234} against a stored {@code 1234.00}.
     * The comparison is by numeric value, not by scale, or the guard would be trivially defeated by
     * a client that happens to serialise the amount differently on the retry.
     */
    @Test
    void recordExpense_repeatIsRecognisedByAmountValue_notByItsScale() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("C4-2")).thenReturn(Optional.of(session));
        when(payouts.findByPosSessionIdOrderByCreatedAtAscIdAsc(any()))
                .thenReturn(List.of(expenseRow("TRANSPORT", new BigDecimal("1234.0000"),
                        "Boda fare to the bank")));

        var req = new PosExpenseRequest(new BigDecimal("1234"), " transport ",
                "Boda Fare To The Bank");

        assertThatThrownBy(() -> service.recordExpense("C4-2", req))
                .isInstanceOf(ConflictException.class);
        verify(payouts, never()).save(any());
    }

    /**
     * Why it is a window and not a uniqueness rule: two identical 2,000 fares an hour apart are an
     * ordinary day in a shop, and refusing the second would lose real cash from the books.
     */
    @Test
    void recordExpense_identicalExpenseAnHourLater_isStillRecorded() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("C4-3")).thenReturn(Optional.of(session));
        when(payouts.findByPosSessionIdOrderByCreatedAtAscIdAsc(any()))
                .thenReturn(List.of(recordedAt(
                        expenseRow("TRANSPORT", new BigDecimal("2000.00"), "Boda fare"),
                        Instant.now().minus(Duration.ofHours(1)))));
        when(payouts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubPayoutGlAccounts();

        var dto = service.recordExpense("C4-3",
                new PosExpenseRequest(new BigDecimal("2000.00"), "TRANSPORT", "Boda fare"));

        assertThat(dto.amount()).isEqualByComparingTo("2000.00");
        verify(payouts).save(any());
        verify(glPosting).post(any());
    }

    /**
     * The escape hatch inside the window, and the reason the note is part of the comparison: two
     * genuinely separate payments described differently are two expenses — and the expense report
     * can then tell them apart, which an identical pair never could.
     */
    @Test
    void recordExpense_sameMoneyAndBucketButADifferentNote_isRecorded() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("C4-4")).thenReturn(Optional.of(session));
        when(payouts.findByPosSessionIdOrderByCreatedAtAscIdAsc(any()))
                .thenReturn(List.of(expenseRow("TRANSPORT", new BigDecimal("2000.00"),
                        "Boda fare to the bank")));
        when(payouts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubPayoutGlAccounts();

        var dto = service.recordExpense("C4-4",
                new PosExpenseRequest(new BigDecimal("2000.00"), "TRANSPORT",
                        "Boda fare to collect stock"));

        assertThat(dto.reason()).isEqualTo("Boda fare to collect stock");
        verify(payouts).save(any());
    }

    /**
     * A drawer drop is not an expense, however alike the numbers look. Merging the two would let a
     * PAID_OUT block the expense that legitimately follows it.
     */
    @Test
    void recordExpense_isNotBlockedByALookalikePaidOut() {
        PosSession session = openSession(1L, new BigDecimal("1000.00"));
        when(sessions.findByUid("C4-5")).thenReturn(Optional.of(session));
        when(payouts.findByPosSessionIdOrderByCreatedAtAscIdAsc(any()))
                .thenReturn(List.of(payoutRow(PosPayoutType.PAID_OUT, new BigDecimal("2000.00"),
                        "Boda fare to the bank")));
        when(payouts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubPayoutGlAccounts();

        var dto = service.recordExpense("C4-5",
                new PosExpenseRequest(new BigDecimal("2000.00"), "TRANSPORT",
                        "Boda fare to the bank"));

        assertThat(dto.payoutType()).isEqualTo(PosPayoutType.EXPENSE);
        verify(payouts).save(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void stubPayoutGlAccounts() {
        ChartOfAccount cash = mock(ChartOfAccount.class);
        when(cash.getId()).thenReturn(CASH_ACCT_ID);
        ChartOfAccount expense = mock(ChartOfAccount.class);
        when(expense.getId()).thenReturn(EXPENSE_ACCT_ID);
        // Cash Short is deliberately stubbed too, and to a DIFFERENT id: if the posting ever falls
        // back to the variance account the assertions below fail on the id rather than passing
        // because nothing was stubbed.
        ChartOfAccount cashShort = mock(ChartOfAccount.class);
        when(cashShort.getId()).thenReturn(CASH_SHORT_ACCT_ID);
        when(glConfig.resolve(anyLong(), eq(GlConfigKey.CASH))).thenReturn(cash);
        when(glConfig.resolve(anyLong(), eq(GlConfigKey.POS_TILL_EXPENSE))).thenReturn(expense);
        when(glConfig.resolve(anyLong(), eq(GlConfigKey.POS_CASH_SHORT))).thenReturn(cashShort);
        // Built before the when(): mockCompany() stubs a mock of its own, and a nested when() inside
        // an in-progress stubbing is what Mockito reports as UnfinishedStubbing.
        Company company = mockCompany(1L, "TZS");
        when(companies.findScopedById(anyLong())).thenReturn(Optional.of(company));
        JournalEntryDto posted = mock(JournalEntryDto.class);
        when(posted.uid()).thenReturn("JRN-1");
        when(glPosting.post(any())).thenReturn(posted);
    }

    /** A {@code pos_expense_idempotency} marker, optionally already stamped with its payout. */
    private static PosExpenseIdempotency marker(String payoutUid) {
        PosExpenseIdempotency m = mock(PosExpenseIdempotency.class);
        when(m.getPayoutUid()).thenReturn(payoutUid);
        return m;
    }

    /** A persisted EXPENSE row with an explicit recorded time (the window guard reads it). */
    private static PosSessionPayout expenseRow(BigDecimal amount, String category, String reason,
                                                Instant createdAt) {
        PosSessionPayout p = mock(PosSessionPayout.class);
        when(p.getPayoutType()).thenReturn(PosPayoutType.EXPENSE);
        when(p.getAmount()).thenReturn(amount);
        when(p.getCategory()).thenReturn(category);
        when(p.getReason()).thenReturn(reason);
        when(p.getCreatedAt()).thenReturn(createdAt);
        return p;
    }

    private PosSessionPayout payoutRow(PosPayoutType type, BigDecimal amount, String reason) {
        return new PosSessionPayout(1L, 1L, 5L, type, amount, reason, 99L);
    }

    /** A persisted till expense, as the cross-session report reads it back. */
    private PosSessionPayout expenseRow(String category, BigDecimal amount) {
        return PosSessionPayout.expense(1L, 1L, 5L, amount, category, "Recorded at the till", 99L);
    }

    /** A persisted till expense with its own note — what the repeated-expense guard compares. */
    private PosSessionPayout expenseRow(String category, BigDecimal amount, String reason) {
        return PosSessionPayout.expense(1L, 1L, 5L, amount, category, reason, 99L);
    }

    /**
     * Back-dates a payout row. {@code created_at} is set once at construction and has no setter (the
     * row is append-only), so a test that needs an older expense has to reach for the field — the
     * same reflection escape hatch {@link #withUid} uses for the JPA-assigned uid.
     */
    private static PosSessionPayout recordedAt(PosSessionPayout payout, Instant when) {
        try {
            var field = PosSessionPayout.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(payout, when);
            return payout;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not back-date the test payout", e);
        }
    }

    /**
     * The uid is normally assigned by JPA's {@code @PrePersist}, which never runs against a mocked
     * repository — so a test that wants to prove the journal points at the right payout has to set
     * it. Reflection keeps {@code UidEntity#setUid} protected for production code.
     */
    private static PosSessionPayout withUid(PosSessionPayout payout, String uid) {
        try {
            var field = com.erp.platform.common.domain.UidEntity.class.getDeclaredField("uid");
            field.setAccessible(true);
            field.set(payout, uid);
            return payout;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not seed a uid for the test payout", e);
        }
    }

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

    /** A fully reconciled session — the state the re-readable Z-read is served from. */
    private PosSession reconciledSession(Long companyId) {
        PosSession s = closedSession(companyId, BigDecimal.ZERO,
                Instant.parse("2026-06-12T22:00:00Z"));
        s.setStatus(PosSessionStatus.RECONCILED);
        s.setReconciledAt(Instant.parse("2026-06-12T22:05:00Z"));
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
