package com.erp.modules.gl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.costing.repository.DimensionRepository;
import com.erp.modules.costing.repository.DimensionValueRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.domain.entity.JournalBatch;
import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.entity.JournalLine;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.ControlType;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalBatchRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for D-1 control-account posting guard (ADR-0040).
 *
 * <p>Verifies:
 * <ol>
 *   <li>A MANUAL journal targeting a blocking control account (AR/AP/INVENTORY/TAX/clearing) is
 *       rejected; CASH/BANK are classified controls that stay manually postable (D-1 refinement,
 *       {@link ControlType#blocksManualPosting()}).</li>
 *   <li>A MANUAL journal targeting an ordinary account (controlType == null) still posts.</li>
 *   <li>A system source (AR_RECEIPT) targeting a control account is permitted
 *       (system posters are exempt from both the allowManualPosting gate and the
 *       control-type gate).</li>
 *   <li>{@link ChartOfAccount#isControlAccount()} returns true iff controlType is non-null.</li>
 *   <li>The seeder stamps controlType on all control accounts and allowManualPosting=false on the
 *       blocking ones (AR/AP/INVENTORY/TAX/clearing); CASH/BANK keep allowManualPosting=true.</li>
 * </ol>
 */
class ControlAccountPostingGuardTest {

    private ChartOfAccountRepository accounts;
    private CompanyRepository        companies;
    private JournalBatchRepository   batches;
    private JournalEntryRepository   entries;
    private JournalLineRepository    lines;
    private FiscalPeriodResolver     periodResolver;
    private JournalBatchNumberGenerator batchNumberGen;
    private DimensionValueRepository dimensionValues;
    private DimensionRepository      dimensionTypes;
    private AuditService             audit;

    private GLPostingServiceImpl service;

    private static final Long COMPANY_ID = 1L;
    private static final Long BRANCH_ID  = 10L;
    private static final Long ACTOR_ID   = 99L;
    private static final Long ACCT_DR    = 100L;
    private static final Long ACCT_CR    = 200L;

    @BeforeEach
    void setUp() {
        accounts        = mock(ChartOfAccountRepository.class);
        companies       = mock(CompanyRepository.class);
        batches         = mock(JournalBatchRepository.class);
        entries         = mock(JournalEntryRepository.class);
        lines           = mock(JournalLineRepository.class);
        periodResolver  = mock(FiscalPeriodResolver.class);
        batchNumberGen  = mock(JournalBatchNumberGenerator.class);
        dimensionValues = mock(DimensionValueRepository.class);
        dimensionTypes  = mock(DimensionRepository.class);
        audit           = mock(AuditService.class);

        service = new GLPostingServiceImpl(
                periodResolver, batchNumberGen,
                accounts, batches, entries, lines,
                companies, audit, dimensionValues, dimensionTypes);

        Company co = mock(Company.class);
        when(co.getBaseCurrency()).thenReturn("TZS");
        when(companies.findById(COMPANY_ID)).thenReturn(Optional.of(co));

        FiscalPeriod period = mock(FiscalPeriod.class);
        when(period.getId()).thenReturn(5L);
        when(periodResolver.resolveOpen(anyLong(), any())).thenReturn(period);

        when(batchNumberGen.next(anyLong())).thenReturn("JB-0001");

        JournalBatch batch = mock(JournalBatch.class);
        when(batch.getId()).thenReturn(1L);
        when(batches.save(any())).thenReturn(batch);

        JournalEntry entry = mock(JournalEntry.class);
        when(entry.getId()).thenReturn(2L);
        when(entry.getUid()).thenReturn("JE-UID-001");
        when(entry.getCompanyId()).thenReturn(COMPANY_ID);
        when(entry.getPostedAt()).thenReturn(null);
        when(entries.save(any())).thenReturn(entry);

        JournalLine line = mock(JournalLine.class);
        when(line.getAccountId()).thenReturn(ACCT_DR);
        when(lines.save(any())).thenReturn(line);

        when(dimensionTypes.findMandatorySlots(anyLong())).thenReturn(List.of());
    }

    private JournalEntryDraft buildDraft(JournalSourceType sourceType, Long drId, Long crId) {
        List<JournalEntryDraft.LineDraft> draftLines = List.of(
                new JournalEntryDraft.LineDraft(
                        drId, new BigDecimal("500"), null,
                        "TZS", "debit", null, null, null, null, null, null, null),
                new JournalEntryDraft.LineDraft(
                        crId, null, new BigDecimal("500"),
                        "TZS", "credit", null, null, null, null, null, null, null)
        );
        return new JournalEntryDraft(
                COMPANY_ID, BRANCH_ID, LocalDate.now(),
                "test", sourceType, "REF-001",
                null, ACTOR_ID, draftLines);
    }

    /** Stubs an account with the given controlType (null = ordinary). allowManualPosting mirrors the
     *  seeder: false for control types that block manual posting (AR/AP/INVENTORY/TAX/clearing); true
     *  for ordinary accounts AND for CASH/BANK (classified controls that stay manually postable). */
    private ChartOfAccount stubAccount(Long id, ControlType controlType) {
        ChartOfAccount acct = mock(ChartOfAccount.class);
        when(acct.getId()).thenReturn(id);
        when(acct.getCompanyId()).thenReturn(COMPANY_ID);
        when(acct.isActive()).thenReturn(true);
        when(acct.getControlType()).thenReturn(controlType);
        when(acct.isControlAccount()).thenReturn(controlType != null);
        boolean allowManual = (controlType == null) || !controlType.blocksManualPosting();
        when(acct.isAllowManualPosting()).thenReturn(allowManual);
        when(acct.getAccountCode()).thenReturn("ACCT-" + id);
        when(acct.getUid()).thenReturn("uid-" + id);
        when(accounts.findById(id)).thenReturn(Optional.of(acct));
        return acct;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. MANUAL journal to a control account is rejected
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void manualJournal_toArControlAccount_isRejected() {
        stubAccount(ACCT_DR, ControlType.AR);
        stubAccount(ACCT_CR, null);

        assertThatThrownBy(() -> service.post(buildDraft(JournalSourceType.MANUAL, ACCT_DR, ACCT_CR)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("does not allow manual posting");
    }

    @Test
    void manualJournal_toApControlAccount_isRejected() {
        stubAccount(ACCT_DR, null);
        stubAccount(ACCT_CR, ControlType.AP);

        assertThatThrownBy(() -> service.post(buildDraft(JournalSourceType.MANUAL, ACCT_DR, ACCT_CR)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("does not allow manual posting");
    }

    @Test
    void manualJournal_toInventoryControlAccount_isRejected() {
        stubAccount(ACCT_DR, ControlType.INVENTORY);
        stubAccount(ACCT_CR, null);

        assertThatThrownBy(() -> service.post(buildDraft(JournalSourceType.MANUAL, ACCT_DR, ACCT_CR)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("does not allow manual posting");
    }

    @Test
    void manualJournal_toTaxControlAccount_isRejected() {
        stubAccount(ACCT_DR, null);
        stubAccount(ACCT_CR, ControlType.TAX);

        assertThatThrownBy(() -> service.post(buildDraft(JournalSourceType.MANUAL, ACCT_DR, ACCT_CR)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("does not allow manual posting");
    }

    /**
     * Belt-and-suspenders: even if allowManualPosting is wrongly left true, the
     * control-type gate fires independently and blocks the MANUAL entry.
     */
    @Test
    void manualJournal_controlAccountWithAllowManualPostingTrue_isStillRejected() {
        ChartOfAccount acct = mock(ChartOfAccount.class);
        when(acct.getId()).thenReturn(ACCT_DR);
        when(acct.getCompanyId()).thenReturn(COMPANY_ID);
        when(acct.isActive()).thenReturn(true);
        // AR is a blocking control type; the belt-and-suspenders gate applies to it even when
        // allowManualPosting is (mis)configured true. (CASH/BANK do NOT block — see the dedicated test.)
        when(acct.getControlType()).thenReturn(ControlType.AR);
        when(acct.isControlAccount()).thenReturn(true);
        // deliberately misconfigured — allowManualPosting=true despite being a blocking control account
        when(acct.isAllowManualPosting()).thenReturn(true);
        when(acct.getAccountCode()).thenReturn("1200");
        when(acct.getUid()).thenReturn("uid-ar");
        when(accounts.findById(ACCT_DR)).thenReturn(Optional.of(acct));
        stubAccount(ACCT_CR, null);

        assertThatThrownBy(() -> service.post(buildDraft(JournalSourceType.MANUAL, ACCT_DR, ACCT_CR)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("control account");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. MANUAL journal to an ordinary account (no controlType) still posts
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void manualJournal_toOrdinaryAccounts_isPermitted() {
        stubAccount(ACCT_DR, null);
        stubAccount(ACCT_CR, null);

        // must not throw
        service.post(buildDraft(JournalSourceType.MANUAL, ACCT_DR, ACCT_CR));
    }

    @Test
    void manualJournal_toCashOrBankAccount_isPermitted_classifiedButNotBlocking() {
        // D-1 refinement: CASH/BANK are classified control accounts but stay manually postable
        // (reconciled via the cash/bank module + bank-rec) — ControlType.blocksManualPosting()==false.
        stubAccount(ACCT_DR, ControlType.CASH);
        stubAccount(ACCT_CR, ControlType.BANK);

        // must not throw — neither the allowManualPosting flag gate nor the control-type gate fires
        service.post(buildDraft(JournalSourceType.MANUAL, ACCT_DR, ACCT_CR));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. System source to a control account is permitted
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void systemJournal_toControlAccount_isPermitted() {
        stubAccount(ACCT_DR, ControlType.AR);
        stubAccount(ACCT_CR, ControlType.AP);

        // AR_RECEIPT is a system source — must not throw
        service.post(buildDraft(JournalSourceType.AR_RECEIPT, ACCT_DR, ACCT_CR));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. isControlAccount() derived method
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void isControlAccount_returnsTrue_whenControlTypeIsSet() {
        ChartOfAccount acct = new ChartOfAccount(1L, "1200", "Accounts Receivable",
                AccountType.ASSET, null);
        acct.setControlType(ControlType.AR);
        assertThat(acct.isControlAccount()).isTrue();
    }

    @Test
    void isControlAccount_returnsFalse_whenControlTypeIsNull() {
        ChartOfAccount acct = new ChartOfAccount(1L, "4100", "Sales Revenue",
                AccountType.INCOME, null);
        assertThat(acct.isControlAccount()).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Seeder stamps controlType + allowManualPosting=false on control accounts
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void seeder_stampsControlTypeAndLocksManualPosting_forControlAccounts() {
        com.erp.modules.gl.repository.ChartOfAccountRepository repo =
                mock(com.erp.modules.gl.repository.ChartOfAccountRepository.class);
        com.erp.modules.iam.repository.CompanyRepository companyRepo =
                mock(com.erp.modules.iam.repository.CompanyRepository.class);
        com.erp.platform.security.ScopeGuard scopeGuard =
                mock(com.erp.platform.security.ScopeGuard.class);
        com.erp.platform.audit.AuditService auditSvc =
                mock(com.erp.platform.audit.AuditService.class);

        // all codes are "new" — repo says they don't exist yet
        when(repo.existsByCompanyIdAndAccountCode(anyLong(), any())).thenReturn(false);

        // capture the saved accounts
        java.util.List<ChartOfAccount> saved = new java.util.ArrayList<>();
        when(repo.save(any(ChartOfAccount.class))).thenAnswer(inv -> {
            ChartOfAccount a = inv.getArgument(0);
            saved.add(a);
            return a;
        });

        ChartOfAccountServiceImpl svc = new ChartOfAccountServiceImpl(
                repo, companyRepo, scopeGuard, auditSvc);
        svc.seedDefaults(1L);

        // AR control (1200) must have controlType=AR and allowManualPosting=false
        ChartOfAccount ar = saved.stream()
                .filter(a -> "1200".equals(a.getAccountCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("1200 not seeded"));
        assertThat(ar.getControlType()).isEqualTo(ControlType.AR);
        assertThat(ar.isAllowManualPosting()).isFalse();

        // AP control (2100) must have controlType=AP and allowManualPosting=false
        ChartOfAccount ap = saved.stream()
                .filter(a -> "2100".equals(a.getAccountCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("2100 not seeded"));
        assertThat(ap.getControlType()).isEqualTo(ControlType.AP);
        assertThat(ap.isAllowManualPosting()).isFalse();

        // Ordinary account (4100 Sales Revenue) must have null controlType and allowManualPosting=true
        ChartOfAccount revenue = saved.stream()
                .filter(a -> "4100".equals(a.getAccountCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("4100 not seeded"));
        assertThat(revenue.getControlType()).isNull();
        assertThat(revenue.isAllowManualPosting()).isTrue();

        // Cash (1000): classified controlType=CASH but stays manually postable (D-1 refinement)
        ChartOfAccount cash = saved.stream()
                .filter(a -> "1000".equals(a.getAccountCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("1000 not seeded"));
        assertThat(cash.getControlType()).isEqualTo(ControlType.CASH);
        assertThat(cash.isAllowManualPosting()).isTrue();

        // Bank (1100): classified controlType=BANK but stays manually postable (D-1 refinement)
        ChartOfAccount bank = saved.stream()
                .filter(a -> "1100".equals(a.getAccountCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("1100 not seeded"));
        assertThat(bank.getControlType()).isEqualTo(ControlType.BANK);
        assertThat(bank.isAllowManualPosting()).isTrue();

        // Net Wages Payable (2550) must have controlType=PAYROLL_CLEARING
        ChartOfAccount wages = saved.stream()
                .filter(a -> "2550".equals(a.getAccountCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("2550 not seeded"));
        assertThat(wages.getControlType()).isEqualTo(ControlType.PAYROLL_CLEARING);
        assertThat(wages.isAllowManualPosting()).isFalse();

        // Realized FX Gain (4920) must have controlType=FX_CLEARING
        ChartOfAccount fxGain = saved.stream()
                .filter(a -> "4920".equals(a.getAccountCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("4920 not seeded"));
        assertThat(fxGain.getControlType()).isEqualTo(ControlType.FX_CLEARING);
        assertThat(fxGain.isAllowManualPosting()).isFalse();
    }
}
