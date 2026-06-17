package com.erp.modules.gl.service;

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
 * Unit tests for the allowManualPosting gate in GLPostingServiceImpl.
 *
 * <p>Verifies that a MANUAL journal targeting an account with allowManualPosting=false
 * is rejected with ConflictException, while a system source (e.g. AR_RECEIPT) targeting
 * the same account is permitted (system posts are not subject to the gate).
 */
class AllowManualPostingGuardTest {

    private ChartOfAccountRepository accounts;
    private CompanyRepository companies;
    private JournalBatchRepository batches;
    private JournalEntryRepository entries;
    private JournalLineRepository lines;
    private FiscalPeriodResolver periodResolver;
    private JournalBatchNumberGenerator batchNumberGen;
    private DimensionValueRepository dimensionValues;
    private DimensionRepository dimensionTypes;
    private AuditService audit;

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

        // Company stub — base currency TZS
        Company co = mock(Company.class);
        when(co.getBaseCurrency()).thenReturn("TZS");
        when(companies.findById(COMPANY_ID)).thenReturn(Optional.of(co));

        // Period stub
        FiscalPeriod period = mock(FiscalPeriod.class);
        when(period.getId()).thenReturn(5L);
        when(periodResolver.resolveOpen(anyLong(), any())).thenReturn(period);

        // Batch / entry / line stubs
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

        // No mandatory dimensions configured
        when(dimensionTypes.findMandatorySlots(anyLong())).thenReturn(List.of());
    }

    /** Builds a balanced two-line draft (DR ACCT_DR / CR ACCT_CR, 1000 TZS each). */
    private JournalEntryDraft buildDraft(JournalSourceType sourceType,
                                         Long drAcctId, Long crAcctId) {
        List<JournalEntryDraft.LineDraft> draftLines = List.of(
                new JournalEntryDraft.LineDraft(
                        drAcctId, new BigDecimal("1000"), null,
                        "TZS", "debit line",
                        null, null, null, null, null, null, null),
                new JournalEntryDraft.LineDraft(
                        crAcctId, null, new BigDecimal("1000"),
                        "TZS", "credit line",
                        null, null, null, null, null, null, null)
        );
        return new JournalEntryDraft(
                COMPANY_ID, BRANCH_ID, LocalDate.now(),
                "test entry", sourceType, "TEST-REF",
                null, ACTOR_ID, draftLines);
    }

    /** Stubs an account: active, same company, allowManualPosting as specified. */
    private ChartOfAccount stubAccount(Long id, boolean allowManualPosting) {
        ChartOfAccount acct = mock(ChartOfAccount.class);
        when(acct.getId()).thenReturn(id);
        when(acct.getCompanyId()).thenReturn(COMPANY_ID);
        when(acct.isActive()).thenReturn(true);
        when(acct.isAllowManualPosting()).thenReturn(allowManualPosting);
        when(acct.getAccountCode()).thenReturn("ACCT-" + id);
        when(acct.getUid()).thenReturn("uid-" + id);
        when(accounts.findById(id)).thenReturn(Optional.of(acct));
        return acct;
    }

    @Test
    void manualJournal_targetingLockedAccount_isRejected() {
        // DR account with allowManualPosting=false (e.g. a control account)
        stubAccount(ACCT_DR, false);
        stubAccount(ACCT_CR, true);

        JournalEntryDraft draft = buildDraft(JournalSourceType.MANUAL, ACCT_DR, ACCT_CR);

        assertThatThrownBy(() -> service.post(draft))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("does not allow manual posting");
    }

    @Test
    void manualJournal_targetingCrLockedAccount_isRejected() {
        // CR account with allowManualPosting=false
        stubAccount(ACCT_DR, true);
        stubAccount(ACCT_CR, false);

        JournalEntryDraft draft = buildDraft(JournalSourceType.MANUAL, ACCT_DR, ACCT_CR);

        assertThatThrownBy(() -> service.post(draft))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("does not allow manual posting");
    }

    @Test
    void systemJournal_targetingLockedAccount_isPermitted() {
        // System sources (AR_RECEIPT, etc.) bypass the manual-posting gate
        stubAccount(ACCT_DR, false);
        stubAccount(ACCT_CR, false);

        JournalEntryDraft draft = buildDraft(JournalSourceType.AR_RECEIPT, ACCT_DR, ACCT_CR);

        // Should not throw — system posters are not subject to the allowManualPosting gate
        service.post(draft);
    }

    @Test
    void manualJournal_bothAccountsAllow_isPermitted() {
        stubAccount(ACCT_DR, true);
        stubAccount(ACCT_CR, true);

        JournalEntryDraft draft = buildDraft(JournalSourceType.MANUAL, ACCT_DR, ACCT_CR);

        // Must not throw
        service.post(draft);
    }
}
