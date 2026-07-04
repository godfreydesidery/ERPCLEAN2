package com.erp.modules.cashbank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.cashbank.domain.dto.CashCountDto;
import com.erp.modules.cashbank.domain.dto.OpenCashCountRequest;
import com.erp.modules.cashbank.domain.dto.RecordDenominationsRequest;
import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.domain.entity.CashCount;
import com.erp.modules.cashbank.domain.entity.CashTransaction;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.domain.enums.CashCountStatus;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.cashbank.repository.CashCountDenominationRepository;
import com.erp.modules.cashbank.repository.CashCountRepository;
import com.erp.modules.cashbank.repository.CashTransactionRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link CashCountServiceImpl} (ADR-0050 D-7 PR-A):
 * <ul>
 *   <li>expected-derivation from bookBalanceAsOf (never hand-typed)</li>
 *   <li>non-CASH till rejected</li>
 *   <li>variance sign (over/short) from recorded denominations</li>
 *   <li>reconcile posts the correct DR/CR draft against the till's own GL account + POS_CASH_OVER/SHORT</li>
 *   <li>reconcile writes a linked DIRECT_ENTRY cash-book row</li>
 *   <li>reconcile is idempotent (second call is a no-op, no double post)</li>
 * </ul>
 */
class CashCountServiceImplTest {

    private CashCountRepository counts;
    private CashCountDenominationRepository denominations;
    private CashBankAccountRepository accounts;
    private CashTransactionRepository txns;
    private CompanyRepository companies;
    private CashBankNumberGenerator numbers;
    private GLConfigResolver glConfig;
    private GLPostingService glPosting;
    private ScopeGuard scopeGuard;
    private AuditService audit;
    private CashCountServiceImpl service;

    @BeforeEach
    void setUp() {
        counts        = mock(CashCountRepository.class);
        denominations = mock(CashCountDenominationRepository.class);
        accounts      = mock(CashBankAccountRepository.class);
        txns          = mock(CashTransactionRepository.class);
        companies     = mock(CompanyRepository.class);
        numbers       = mock(CashBankNumberGenerator.class);
        glConfig      = mock(GLConfigResolver.class);
        glPosting     = mock(GLPostingService.class);
        scopeGuard    = mock(ScopeGuard.class);
        audit         = mock(AuditService.class);

        when(numbers.nextCashCount(anyLong())).thenReturn("CC-0001");
        when(numbers.nextTransaction(anyLong())).thenReturn("CBTX-0001");
        when(counts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(denominations.findByCashCountIdOrderByDenominationDesc(any())).thenReturn(List.of());

        service = new CashCountServiceImpl(counts, denominations, accounts, txns, companies,
                numbers, glConfig, glPosting, scopeGuard, audit);

        RequestContext.set(new RequestContext.Principal(99L, "cashier", false, 1L, 5L, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // open(): expected is DERIVED, never typed (ADR-0050 D-7.2)
    // -------------------------------------------------------------------------

    @Test
    void open_derivesExpectedFromBookBalanceAsOf() {
        Company company = mockCompany(1L, "TZS");
        CashBankAccount till = mockTill(10L, CashBankAccountType.CASH, true, 5L);
        when(companies.findByUid("CO1")).thenReturn(Optional.of(company));
        when(accounts.findByCompanyIdAndUid(1L, "TILL1")).thenReturn(Optional.of(till));
        when(txns.bookBalanceAsOf(10L, LocalDate.of(2026, 7, 4))).thenReturn(new BigDecimal("500.00"));
        when(accounts.findByCompanyIdAndId(1L, 10L)).thenReturn(Optional.of(till));

        CashCountDto dto = service.open(
                new OpenCashCountRequest("CO1", "TILL1", LocalDate.of(2026, 7, 4)));

        assertThat(dto.expectedAmount()).isEqualByComparingTo("500.00");
        assertThat(dto.countedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.varianceAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.status()).isEqualTo(CashCountStatus.OPEN);
        assertThat(dto.countNumber()).isEqualTo("CC-0001");
    }

    @Test
    void open_noPriorTransactions_expectedIsZero_notNull() {
        Company company = mockCompany(1L, "TZS");
        CashBankAccount till = mockTill(10L, CashBankAccountType.CASH, true, 5L);
        when(companies.findByUid("CO1")).thenReturn(Optional.of(company));
        when(accounts.findByCompanyIdAndUid(1L, "TILL1")).thenReturn(Optional.of(till));
        when(txns.bookBalanceAsOf(10L, LocalDate.of(2026, 7, 4))).thenReturn(null);
        when(accounts.findByCompanyIdAndId(1L, 10L)).thenReturn(Optional.of(till));

        CashCountDto dto = service.open(
                new OpenCashCountRequest("CO1", "TILL1", LocalDate.of(2026, 7, 4)));

        assertThat(dto.expectedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void open_bankAccount_rejected() {
        Company company = mockCompany(1L, "TZS");
        CashBankAccount bankAcct = mockTill(11L, CashBankAccountType.BANK, true, 5L);
        when(companies.findByUid("CO1")).thenReturn(Optional.of(company));
        when(accounts.findByCompanyIdAndUid(1L, "BANK1")).thenReturn(Optional.of(bankAcct));

        assertThatThrownBy(() -> service.open(
                new OpenCashCountRequest("CO1", "BANK1", LocalDate.now())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CASH till");
    }

    @Test
    void open_inactiveTill_rejected() {
        Company company = mockCompany(1L, "TZS");
        CashBankAccount till = mockTill(10L, CashBankAccountType.CASH, false, 5L);
        when(companies.findByUid("CO1")).thenReturn(Optional.of(company));
        when(accounts.findByCompanyIdAndUid(1L, "TILL1")).thenReturn(Optional.of(till));

        assertThatThrownBy(() -> service.open(
                new OpenCashCountRequest("CO1", "TILL1", LocalDate.now())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void open_duplicateLiveCountForSameTillAndDate_rejected() {
        // Guard against a second live count whose variance would post to GL/cash-book twice (D-7 review).
        Company company = mockCompany(1L, "TZS");
        CashBankAccount till = mockTill(10L, CashBankAccountType.CASH, true, 5L);
        when(companies.findByUid("CO1")).thenReturn(Optional.of(company));
        when(accounts.findByCompanyIdAndUid(1L, "TILL1")).thenReturn(Optional.of(till));
        when(counts.existsByCashAccountIdAndBusinessDateAndStatusIn(anyLong(), any(), any()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.open(
                new OpenCashCountRequest("CO1", "TILL1", LocalDate.of(2026, 7, 4))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");
        verify(counts, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // recordDenominations(): counted = Sum(denomination x quantity); variance sign
    // -------------------------------------------------------------------------

    @Test
    void recordDenominations_over_positiveVariance() {
        CashCount count = openedCount(1L, 5L, 10L, new BigDecimal("1000"));
        when(counts.findByUid("U1")).thenReturn(Optional.of(count));
        CashBankAccount till = mockTill(10L, CashBankAccountType.CASH, true, 5L);
        when(accounts.findByCompanyIdAndId(1L, 10L)).thenReturn(Optional.of(till));

        var req = new RecordDenominationsRequest(List.of(
                new RecordDenominationsRequest.Line(new BigDecimal("500"), 2),
                new RecordDenominationsRequest.Line(new BigDecimal("200"), 1)));

        CashCountDto dto = service.recordDenominations("U1", req);

        assertThat(dto.countedAmount()).isEqualByComparingTo("1200");
        assertThat(dto.varianceAmount()).isEqualByComparingTo("200");
        assertThat(dto.status()).isEqualTo(CashCountStatus.COUNTED);
        verify(denominations).deleteByCashCountId(count.getId());
        verify(denominations).saveAll(any());
    }

    @Test
    void recordDenominations_short_negativeVariance() {
        CashCount count = openedCount(1L, 5L, 10L, new BigDecimal("1000"));
        when(counts.findByUid("U2")).thenReturn(Optional.of(count));
        CashBankAccount till = mockTill(10L, CashBankAccountType.CASH, true, 5L);
        when(accounts.findByCompanyIdAndId(1L, 10L)).thenReturn(Optional.of(till));

        var req = new RecordDenominationsRequest(List.of(
                new RecordDenominationsRequest.Line(new BigDecimal("500"), 1),
                new RecordDenominationsRequest.Line(new BigDecimal("200"), 1)));

        CashCountDto dto = service.recordDenominations("U2", req);

        assertThat(dto.countedAmount()).isEqualByComparingTo("700");
        assertThat(dto.varianceAmount()).isEqualByComparingTo("-300");
        assertThat(dto.status()).isEqualTo(CashCountStatus.COUNTED);
    }

    @Test
    void recordDenominations_alreadyReconciled_rejected() {
        CashCount count = openedCount(1L, 5L, 10L, new BigDecimal("1000"));
        count.markCounted(new BigDecimal("1000"), BigDecimal.ZERO, 99L);
        count.markReconciled(null, 99L);
        when(counts.findByUid("U3")).thenReturn(Optional.of(count));

        var req = new RecordDenominationsRequest(List.of(
                new RecordDenominationsRequest.Line(new BigDecimal("500"), 2)));

        assertThatThrownBy(() -> service.recordDenominations("U3", req))
                .isInstanceOf(ConflictException.class);
    }

    // -------------------------------------------------------------------------
    // reconcile(): posts the correct DR/CR draft + writes the linked cash-book row
    // -------------------------------------------------------------------------

    @Test
    void reconcile_overVariance_postsDrTillGlCrOverAccount_andWritesDirectEntryCashRow() {
        CashCount count = countedCount(1L, 5L, 10L,
                new BigDecimal("1000"), new BigDecimal("1200"), new BigDecimal("200"));
        when(counts.findByUid("U4")).thenReturn(Optional.of(count));

        CashBankAccount till = mockTill(10L, CashBankAccountType.CASH, true, 5L);
        when(accounts.findByCompanyIdAndId(1L, 10L)).thenReturn(Optional.of(till));

        ChartOfAccount overAcct = mockCoa(20L);
        when(glConfig.resolve(1L, GlConfigKey.POS_CASH_OVER)).thenReturn(overAcct);
        when(glPosting.post(any())).thenReturn(journalDto("JRNL-OVER"));

        CashCountDto dto = service.reconcile("U4");

        assertThat(dto.status()).isEqualTo(CashCountStatus.RECONCILED);
        assertThat(dto.journalEntryRef()).isEqualTo("JRNL-OVER");

        ArgumentCaptor<JournalEntryDraft> draftCaptor = ArgumentCaptor.forClass(JournalEntryDraft.class);
        verify(glPosting).post(draftCaptor.capture());
        JournalEntryDraft draft = draftCaptor.getValue();
        assertThat(draft.lines()).hasSize(2);
        var debit = draft.lines().get(0);
        var credit = draft.lines().get(1);
        assertThat(debit.accountId()).isEqualTo(100L); // the till's own linked GL account
        assertThat(debit.debitAmount()).isEqualByComparingTo("200");
        assertThat(debit.creditAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(credit.accountId()).isEqualTo(20L); // POS_CASH_OVER
        assertThat(credit.creditAmount()).isEqualByComparingTo("200");
        assertThat(credit.debitAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        ArgumentCaptor<CashTransaction> txnCaptor = ArgumentCaptor.forClass(CashTransaction.class);
        verify(txns).save(txnCaptor.capture());
        CashTransaction txn = txnCaptor.getValue();
        assertThat(txn.getDirection()).isEqualTo(CashTxnDirection.IN);
        assertThat(txn.getAmount()).isEqualByComparingTo("200");
        assertThat(txn.getCounterGlAccountId()).isEqualTo(20L);
        assertThat(txn.getJournalEntryRef()).isEqualTo("JRNL-OVER");
        assertThat(txn.getCashBankAccountId()).isEqualTo(10L);
    }

    @Test
    void reconcile_shortVariance_postsDrShortAccountCrTillGl_directionOut() {
        CashCount count = countedCount(1L, 5L, 10L,
                new BigDecimal("1000"), new BigDecimal("700"), new BigDecimal("-300"));
        when(counts.findByUid("U5")).thenReturn(Optional.of(count));

        CashBankAccount till = mockTill(10L, CashBankAccountType.CASH, true, 5L);
        when(accounts.findByCompanyIdAndId(1L, 10L)).thenReturn(Optional.of(till));

        ChartOfAccount shortAcct = mockCoa(30L);
        when(glConfig.resolve(1L, GlConfigKey.POS_CASH_SHORT)).thenReturn(shortAcct);
        when(glPosting.post(any())).thenReturn(journalDto("JRNL-SHORT"));

        CashCountDto dto = service.reconcile("U5");

        assertThat(dto.status()).isEqualTo(CashCountStatus.RECONCILED);

        ArgumentCaptor<JournalEntryDraft> draftCaptor = ArgumentCaptor.forClass(JournalEntryDraft.class);
        verify(glPosting).post(draftCaptor.capture());
        JournalEntryDraft draft = draftCaptor.getValue();
        var debit = draft.lines().get(0);
        var credit = draft.lines().get(1);
        assertThat(debit.accountId()).isEqualTo(30L); // POS_CASH_SHORT
        assertThat(debit.debitAmount()).isEqualByComparingTo("300");
        assertThat(credit.accountId()).isEqualTo(100L); // the till's own linked GL account
        assertThat(credit.creditAmount()).isEqualByComparingTo("300");

        ArgumentCaptor<CashTransaction> txnCaptor = ArgumentCaptor.forClass(CashTransaction.class);
        verify(txns).save(txnCaptor.capture());
        assertThat(txnCaptor.getValue().getDirection()).isEqualTo(CashTxnDirection.OUT);
        assertThat(txnCaptor.getValue().getAmount()).isEqualByComparingTo("300");
        assertThat(txnCaptor.getValue().getCounterGlAccountId()).isEqualTo(30L);
    }

    @Test
    void reconcile_zeroVariance_noGlPost_noCashRowWritten() {
        CashCount count = countedCount(1L, 5L, 10L,
                new BigDecimal("1000"), new BigDecimal("1000"), BigDecimal.ZERO);
        when(counts.findByUid("U6")).thenReturn(Optional.of(count));

        CashCountDto dto = service.reconcile("U6");

        assertThat(dto.status()).isEqualTo(CashCountStatus.RECONCILED);
        assertThat(dto.journalEntryRef()).isNull();
        verify(glPosting, never()).post(any());
        verify(txns, never()).save(any());
    }

    @Test
    void reconcile_alreadyReconciled_isIdempotentNoOp() {
        CashCount count = countedCount(1L, 5L, 10L,
                new BigDecimal("1000"), new BigDecimal("1200"), new BigDecimal("200"));
        count.markReconciled("JRNL-ORIGINAL", 99L);
        when(counts.findByUid("U7")).thenReturn(Optional.of(count));

        CashCountDto dto = service.reconcile("U7");

        assertThat(dto.status()).isEqualTo(CashCountStatus.RECONCILED);
        assertThat(dto.journalEntryRef()).isEqualTo("JRNL-ORIGINAL");
        verify(glPosting, never()).post(any());
        verify(txns, never()).save(any());
    }

    @Test
    void reconcile_stillOpen_throwsConflict() {
        CashCount count = openedCount(1L, 5L, 10L, new BigDecimal("1000"));
        when(counts.findByUid("U8")).thenReturn(Optional.of(count));

        assertThatThrownBy(() -> service.reconcile("U8"))
                .isInstanceOf(ConflictException.class);
        verify(glPosting, never()).post(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private CashCount openedCount(Long companyId, Long branchId, Long cashAccountId, BigDecimal expected) {
        return new CashCount(companyId, branchId, cashAccountId, "CC-0001", 99L,
                LocalDate.of(2026, 7, 4), expected, "TZS", 99L);
    }

    private CashCount countedCount(Long companyId, Long branchId, Long cashAccountId,
                                    BigDecimal expected, BigDecimal counted, BigDecimal variance) {
        CashCount count = openedCount(companyId, branchId, cashAccountId, expected);
        count.markCounted(counted, variance, 99L);
        return count;
    }

    private Company mockCompany(Long id, String currency) {
        Company c = mock(Company.class);
        when(c.getId()).thenReturn(id);
        when(c.getBaseCurrency()).thenReturn(currency);
        return c;
    }

    private CashBankAccount mockTill(Long id, CashBankAccountType type, boolean active, Long branchId) {
        CashBankAccount t = mock(CashBankAccount.class);
        when(t.getId()).thenReturn(id);
        when(t.getAccountType()).thenReturn(type);
        when(t.isActive()).thenReturn(active);
        when(t.getBranchId()).thenReturn(branchId);
        when(t.getGlAccountId()).thenReturn(100L);
        when(t.getUid()).thenReturn("TILL-UID-" + id);
        when(t.getName()).thenReturn("Till " + id);
        return t;
    }

    private ChartOfAccount mockCoa(Long id) {
        ChartOfAccount coa = mock(ChartOfAccount.class);
        when(coa.getId()).thenReturn(id);
        return coa;
    }

    private JournalEntryDto journalDto(String uid) {
        return new JournalEntryDto(99L, uid, null, null, null, null, null, null, null, null,
                null, false, null, null, null, null, null);
    }
}
