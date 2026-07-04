package com.erp.modules.cashbank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.cashbank.domain.dto.CreatePettyCashFundRequest;
import com.erp.modules.cashbank.domain.dto.PettyCashFundDto;
import com.erp.modules.cashbank.domain.dto.PettyCashTransactionDto;
import com.erp.modules.cashbank.domain.dto.RecordPettyCashTxnRequest;
import com.erp.modules.cashbank.domain.dto.UpdatePettyCashFundRequest;
import com.erp.modules.cashbank.domain.entity.PettyCashFund;
import com.erp.modules.cashbank.domain.enums.PettyCashTxnType;
import com.erp.modules.cashbank.repository.PettyCashFundRepository;
import com.erp.modules.cashbank.repository.PettyCashTransactionRepository;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PettyCashServiceImpl} (ADR-0050 D-7 PR-B).
 *
 * <p>RECORD-ONLY: covers balance math per transaction type (disburse decrements + stamps
 * {@code balanceAfter}, replenish increments, adjustment is signed), the negative-balance soft
 * block, and the scope guard on every write/read path.
 */
class PettyCashServiceImplTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long BRANCH_ID  = 5L;

    private PettyCashFundRepository        funds;
    private PettyCashTransactionRepository txns;
    private CompanyRepository              companies;
    private BranchRepository               branches;
    private AppUserRepository              users;
    private ChartOfAccountRepository       glAccounts;
    private CashBankNumberGenerator        numbers;
    private ScopeGuard                     scopeGuard;
    private AuditService                   audit;
    private PettyCashServiceImpl           service;

    @BeforeEach
    void setUp() {
        funds      = mock(PettyCashFundRepository.class);
        txns       = mock(PettyCashTransactionRepository.class);
        companies  = mock(CompanyRepository.class);
        branches   = mock(BranchRepository.class);
        users      = mock(AppUserRepository.class);
        glAccounts = mock(ChartOfAccountRepository.class);
        numbers    = mock(CashBankNumberGenerator.class);
        scopeGuard = mock(ScopeGuard.class);
        audit      = mock(AuditService.class);

        when(numbers.nextPettyCashFund(anyLong())).thenReturn("PCF-0001");
        when(numbers.nextPettyCashTxn(anyLong())).thenReturn("PC-0001");
        when(funds.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(txns.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new PettyCashServiceImpl(funds, txns, companies, branches, users, glAccounts,
                numbers, scopeGuard, audit);

        RequestContext.set(new RequestContext.Principal(99L, "cashier", false, COMPANY_ID, BRANCH_ID, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // createFund()
    // -------------------------------------------------------------------------

    @Test
    void createFund_happyPath_zeroBalance_defaultsCurrencyToCompanyBase() {
        Company company = mockCompany(COMPANY_ID, "TZS");
        when(companies.findByUid("CO1")).thenReturn(Optional.of(company));
        when(funds.existsByCompanyIdAndCode(COMPANY_ID, "PETTY-1")).thenReturn(false);

        PettyCashFundDto dto = service.createFund(new CreatePettyCashFundRequest(
                "CO1", "PETTY-1", "Head Office Petty Cash", null, new BigDecimal("500"), null));

        assertThat(dto.code()).isEqualTo("PETTY-1");
        assertThat(dto.floatAmount()).isEqualByComparingTo("500");
        assertThat(dto.balanceAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.currency()).isEqualTo("TZS");
        assertThat(dto.branchId()).isEqualTo(BRANCH_ID);
        assertThat(dto.status()).isEqualTo(MasterStatus.ACTIVE);
    }

    @Test
    void createFund_blankCode_autoGeneratesViaNumberGenerator() {
        Company company = mockCompany(COMPANY_ID, "TZS");
        when(companies.findByUid("CO1")).thenReturn(Optional.of(company));
        when(funds.existsByCompanyIdAndCode(COMPANY_ID, "PCF-0001")).thenReturn(false);

        PettyCashFundDto dto = service.createFund(new CreatePettyCashFundRequest(
                "CO1", null, "Head Office Petty Cash", null, null, null));

        assertThat(dto.code()).isEqualTo("PCF-0001");
    }

    @Test
    void createFund_duplicateCode_rejected() {
        Company company = mockCompany(COMPANY_ID, "TZS");
        when(companies.findByUid("CO1")).thenReturn(Optional.of(company));
        when(funds.existsByCompanyIdAndCode(COMPANY_ID, "PETTY-1")).thenReturn(true);

        assertThatThrownBy(() -> service.createFund(new CreatePettyCashFundRequest(
                "CO1", "PETTY-1", "Dup", null, null, null)))
                .isInstanceOf(ConflictException.class);
        verify(funds, never()).save(any());
    }

    @Test
    void createFund_noBranchContextAndNoDefaultBranch_rejected() {
        Company company = mockCompany(COMPANY_ID, "TZS");
        when(companies.findByUid("CO1")).thenReturn(Optional.of(company));
        when(funds.existsByCompanyIdAndCode(any(), any())).thenReturn(false);
        when(branches.findByCompanyIdAndIsDefaultTrue(COMPANY_ID)).thenReturn(Optional.empty());

        RequestContext.clear(); // no branch context

        assertThatThrownBy(() -> service.createFund(new CreatePettyCashFundRequest(
                "CO1", "PETTY-1", "No Branch", null, null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void createFund_callerActiveBranchBelongsToDifferentCompany_fallsBackToTargetDefaultBranch() {
        // Root caller's active company/branch (set in setUp) is COMPANY_ID/BRANCH_ID; the target
        // company for this fund is a DIFFERENT company, so the caller's active branch must NOT be
        // stamped onto the fund (cross-tenant confused-deputy) — it must fall back to the target
        // company's own default branch instead.
        Long otherCompanyId = 2L;
        Long otherCompanyDefaultBranchId = 20L;
        Company company = mockCompany(otherCompanyId, "TZS");
        when(companies.findByUid("CO2")).thenReturn(Optional.of(company));
        when(funds.existsByCompanyIdAndCode(any(), any())).thenReturn(false);
        Branch otherBranch = mock(Branch.class);
        when(otherBranch.getId()).thenReturn(otherCompanyDefaultBranchId);
        when(branches.findByCompanyIdAndIsDefaultTrue(otherCompanyId)).thenReturn(Optional.of(otherBranch));

        PettyCashFundDto dto = service.createFund(new CreatePettyCashFundRequest(
                "CO2", "PETTY-CROSS", "Cross Company Fund", null, null, null));

        assertThat(dto.branchId()).isEqualTo(otherCompanyDefaultBranchId);
        assertThat(dto.branchId()).isNotEqualTo(BRANCH_ID);
    }

    // -------------------------------------------------------------------------
    // recordTransaction(): balance math per type (ADR-0050 D-7 core)
    // -------------------------------------------------------------------------

    @Test
    void recordTransaction_disbursement_decrementsBalance_andStampsBalanceAfter() {
        PettyCashFund fund = activeFund(new BigDecimal("1000"));
        when(funds.findByUid("F1")).thenReturn(Optional.of(fund));

        PettyCashTransactionDto dto = service.recordTransaction("F1", new RecordPettyCashTxnRequest(
                PettyCashTxnType.DISBURSEMENT, new BigDecimal("300"), LocalDate.of(2026, 7, 4),
                null, "REF-1", "Stationery"));

        assertThat(dto.amount()).isEqualByComparingTo("300");
        assertThat(dto.balanceAfter()).isEqualByComparingTo("700");
        assertThat(fund.getBalanceAmount()).isEqualByComparingTo("700");
        assertThat(dto.txnType()).isEqualTo(PettyCashTxnType.DISBURSEMENT);
        assertThat(dto.txnNumber()).isEqualTo("PC-0001");
    }

    @Test
    void recordTransaction_replenishment_incrementsBalance() {
        PettyCashFund fund = activeFund(new BigDecimal("200"));
        when(funds.findByUid("F2")).thenReturn(Optional.of(fund));

        PettyCashTransactionDto dto = service.recordTransaction("F2", new RecordPettyCashTxnRequest(
                PettyCashTxnType.REPLENISHMENT, new BigDecimal("800"), LocalDate.of(2026, 7, 4),
                null, null, "Top-up"));

        assertThat(dto.balanceAfter()).isEqualByComparingTo("1000");
        assertThat(fund.getBalanceAmount()).isEqualByComparingTo("1000");
    }

    @Test
    void recordTransaction_adjustment_positiveSigned_increasesBalance_persistsPositiveAmount() {
        PettyCashFund fund = activeFund(new BigDecimal("500"));
        when(funds.findByUid("F3")).thenReturn(Optional.of(fund));

        PettyCashTransactionDto dto = service.recordTransaction("F3", new RecordPettyCashTxnRequest(
                PettyCashTxnType.ADJUSTMENT, new BigDecimal("50"), LocalDate.of(2026, 7, 4),
                null, null, "Count found extra cash"));

        assertThat(dto.balanceAfter()).isEqualByComparingTo("550");
        // the persisted amount is the SIGNED request amount (positive here)
        assertThat(dto.amount()).isEqualByComparingTo("50");
        assertThat(dto.amount().signum()).isEqualTo(1);
    }

    @Test
    void recordTransaction_adjustment_negativeSigned_decreasesBalance_persistsSignedNegativeAmount() {
        PettyCashFund fund = activeFund(new BigDecimal("500"));
        when(funds.findByUid("F4")).thenReturn(Optional.of(fund));

        PettyCashTransactionDto dto = service.recordTransaction("F4", new RecordPettyCashTxnRequest(
                PettyCashTxnType.ADJUSTMENT, new BigDecimal("-75"), LocalDate.of(2026, 7, 4),
                null, null, "Count found a shortfall"));

        assertThat(dto.balanceAfter()).isEqualByComparingTo("425");
        // the persisted amount is the SIGNED request amount (negative here), not the magnitude —
        // the ledger must be able to show whether an adjustment increased or decreased the balance.
        assertThat(dto.amount()).isEqualByComparingTo("-75");
        assertThat(dto.amount().signum()).isEqualTo(-1);
        assertThat(fund.getBalanceAmount()).isEqualByComparingTo("425");
    }

    @Test
    void recordTransaction_disbursementExceedingBalance_softBlocked_noSaveOfBalance() {
        PettyCashFund fund = activeFund(new BigDecimal("100"));
        when(funds.findByUid("F5")).thenReturn(Optional.of(fund));

        assertThatThrownBy(() -> service.recordTransaction("F5", new RecordPettyCashTxnRequest(
                PettyCashTxnType.DISBURSEMENT, new BigDecimal("500"), LocalDate.of(2026, 7, 4),
                null, null, "Too much")))
                .isInstanceOf(ConflictException.class);
        verify(txns, never()).save(any());
    }

    @Test
    void recordTransaction_negativeAdjustmentExceedingBalance_softBlocked() {
        PettyCashFund fund = activeFund(new BigDecimal("100"));
        when(funds.findByUid("F6")).thenReturn(Optional.of(fund));

        assertThatThrownBy(() -> service.recordTransaction("F6", new RecordPettyCashTxnRequest(
                PettyCashTxnType.ADJUSTMENT, new BigDecimal("-150"), LocalDate.of(2026, 7, 4),
                null, null, "Big shortfall")))
                .isInstanceOf(ConflictException.class);
        verify(txns, never()).save(any());
    }

    @Test
    void recordTransaction_replenishmentWithNegativeAmount_rejected() {
        PettyCashFund fund = activeFund(new BigDecimal("100"));
        when(funds.findByUid("F7")).thenReturn(Optional.of(fund));

        assertThatThrownBy(() -> service.recordTransaction("F7", new RecordPettyCashTxnRequest(
                PettyCashTxnType.REPLENISHMENT, new BigDecimal("-10"), LocalDate.of(2026, 7, 4),
                null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordTransaction_zeroAmount_rejected() {
        PettyCashFund fund = activeFund(new BigDecimal("100"));
        when(funds.findByUid("F8")).thenReturn(Optional.of(fund));

        assertThatThrownBy(() -> service.recordTransaction("F8", new RecordPettyCashTxnRequest(
                PettyCashTxnType.ADJUSTMENT, BigDecimal.ZERO, LocalDate.of(2026, 7, 4),
                null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordTransaction_inactiveFund_rejected() {
        PettyCashFund fund = activeFund(new BigDecimal("100"));
        fund.setStatus(MasterStatus.INACTIVE);
        when(funds.findByUid("F9")).thenReturn(Optional.of(fund));

        assertThatThrownBy(() -> service.recordTransaction("F9", new RecordPettyCashTxnRequest(
                PettyCashTxnType.DISBURSEMENT, new BigDecimal("10"), LocalDate.of(2026, 7, 4),
                null, null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void recordTransaction_neverPostsGl_journalEntryRefStaysNull() {
        // RECORD-ONLY (ADR-0050 D-7.1): the DTO carries no journalEntryRef field at all — assert the
        // txn is captured with glAccountId resolved but no GL posting collaborator is even injected.
        PettyCashFund fund = activeFund(new BigDecimal("1000"));
        when(funds.findByUid("F10")).thenReturn(Optional.of(fund));
        ChartOfAccount coa = mock(ChartOfAccount.class);
        when(coa.getId()).thenReturn(77L);
        when(coa.getUid()).thenReturn("GL-UID-77");
        when(glAccounts.findByCompanyIdAndUid(COMPANY_ID, "GLUID")).thenReturn(Optional.of(coa));
        when(glAccounts.findScopedById(77L)).thenReturn(Optional.of(coa));

        PettyCashTransactionDto dto = service.recordTransaction("F10", new RecordPettyCashTxnRequest(
                PettyCashTxnType.DISBURSEMENT, new BigDecimal("100"), LocalDate.of(2026, 7, 4),
                "GLUID", null, "Office supplies"));

        assertThat(dto.glAccountUid()).isEqualTo("GL-UID-77");
    }

    @Test
    void recordTransaction_unknownGlAccountUid_notFound() {
        PettyCashFund fund = activeFund(new BigDecimal("1000"));
        when(funds.findByUid("F11")).thenReturn(Optional.of(fund));
        when(glAccounts.findByCompanyIdAndUid(COMPANY_ID, "BOGUS")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordTransaction("F11", new RecordPettyCashTxnRequest(
                PettyCashTxnType.DISBURSEMENT, new BigDecimal("10"), LocalDate.of(2026, 7, 4),
                "BOGUS", null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // Scope guard
    // -------------------------------------------------------------------------

    @Test
    void getFund_assertsScopeGuard() {
        PettyCashFund fund = activeFund(BigDecimal.ZERO);
        when(funds.findByUid("F12")).thenReturn(Optional.of(fund));

        service.getFund("F12");

        verify(scopeGuard).assertCanActIn(any(), org.mockito.ArgumentMatchers.eq(COMPANY_ID));
    }

    @Test
    void listFunds_assertsScopeGuard() {
        when(funds.findByCompanyIdOrderByNameAsc(COMPANY_ID)).thenReturn(java.util.List.of());

        service.listFunds(COMPANY_ID);

        verify(scopeGuard).assertCanActIn(any(), org.mockito.ArgumentMatchers.eq(COMPANY_ID));
    }

    // -------------------------------------------------------------------------
    // updateFund()
    // -------------------------------------------------------------------------

    @Test
    void updateFund_partialUpdate_onlyAppliesNonNullFields() {
        PettyCashFund fund = activeFund(new BigDecimal("100"));
        when(funds.findByUid("F13")).thenReturn(Optional.of(fund));

        PettyCashFundDto dto = service.updateFund("F13", new UpdatePettyCashFundRequest(
                "New Name", null, new BigDecimal("999"), null));

        assertThat(dto.name()).isEqualTo("New Name");
        assertThat(dto.floatAmount()).isEqualByComparingTo("999");
        assertThat(dto.status()).isEqualTo(MasterStatus.ACTIVE);
    }

    @Test
    void updateFund_statusChange_archivesFund() {
        PettyCashFund fund = activeFund(new BigDecimal("100"));
        when(funds.findByUid("F14")).thenReturn(Optional.of(fund));

        PettyCashFundDto dto = service.updateFund("F14", new UpdatePettyCashFundRequest(
                null, null, null, MasterStatus.ARCHIVED));

        assertThat(dto.status()).isEqualTo(MasterStatus.ARCHIVED);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PettyCashFund activeFund(BigDecimal openingBalance) {
        PettyCashFund fund = new PettyCashFund(COMPANY_ID, BRANCH_ID, "PETTY", "Petty Cash",
                null, BigDecimal.ZERO, "TZS", 1L);
        if (openingBalance.compareTo(BigDecimal.ZERO) != 0) {
            fund.applyReplenishment(openingBalance);
        }
        return fund;
    }

    private Company mockCompany(Long id, String currency) {
        Company c = mock(Company.class);
        when(c.getId()).thenReturn(id);
        when(c.getBaseCurrency()).thenReturn(currency);
        return c;
    }
}
