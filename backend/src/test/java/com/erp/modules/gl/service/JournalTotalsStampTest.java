package com.erp.modules.gl.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit test for defect #1: GLPostingServiceImpl must stamp totalDebit and totalCredit
 * onto the JournalEntry before persisting it, so the DTO exposes non-null totals.
 *
 * <p>Before the fix, totalDebit/totalCredit were computed for the balance check but never
 * set on the entity; both fields were null in every response DTO.
 */
class JournalTotalsStampTest {

    private ChartOfAccountRepository  accounts;
    private CompanyRepository          companies;
    private JournalBatchRepository     batches;
    private JournalEntryRepository     entries;
    private JournalLineRepository      lines;
    private FiscalPeriodResolver       periodResolver;
    private JournalBatchNumberGenerator batchNumberGen;
    private DimensionValueRepository   dimensionValues;
    private DimensionRepository        dimensionTypes;
    private AuditService               audit;

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

        // entries.save() passes through the real JournalEntry so we can verify its state
        when(entries.save(any(JournalEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        JournalLine jl = mock(JournalLine.class);
        when(jl.getAccountId()).thenReturn(ACCT_DR);
        when(lines.save(any())).thenReturn(jl);

        when(dimensionTypes.findMandatorySlots(anyLong())).thenReturn(List.of());

        // Stub both accounts: active, same company, allow manual posting
        stubAccount(ACCT_DR, true);
        stubAccount(ACCT_CR, true);
    }

    private void stubAccount(Long id, boolean allowManual) {
        ChartOfAccount acct = mock(ChartOfAccount.class);
        when(acct.getId()).thenReturn(id);
        when(acct.getCompanyId()).thenReturn(COMPANY_ID);
        when(acct.isActive()).thenReturn(true);
        when(acct.isAllowManualPosting()).thenReturn(allowManual);
        when(acct.getAccountCode()).thenReturn("ACCT-" + id);
        when(acct.getUid()).thenReturn("uid-" + id);
        when(accounts.findById(id)).thenReturn(Optional.of(acct));
    }

    /**
     * Defect #1: after posting a balanced journal, the captured JournalEntry passed to
     * entries.save() must carry non-null totalDebit == totalCredit == the posted amount.
     */
    @Test
    void post_balancedJournal_stampsCorrectTotalsOnEntry() {
        BigDecimal amount = new BigDecimal("750.00");

        JournalEntryDraft draft = new JournalEntryDraft(
                COMPANY_ID, BRANCH_ID, LocalDate.now(),
                "totals stamp test", JournalSourceType.MANUAL, null, null, ACTOR_ID,
                List.of(
                        new JournalEntryDraft.LineDraft(
                                ACCT_DR, amount, null, "TZS", "debit",
                                null, null, null, null, null, null, null),
                        new JournalEntryDraft.LineDraft(
                                ACCT_CR, null, amount, "TZS", "credit",
                                null, null, null, null, null, null, null)
                ));

        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        when(entries.save(entryCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.post(draft);

        JournalEntry saved = entryCaptor.getValue();
        assertThat(saved.getTotalDebit())
                .as("totalDebit must be stamped before save (Defect #1)")
                .isNotNull()
                .isEqualByComparingTo(amount);
        assertThat(saved.getTotalCredit())
                .as("totalCredit must be stamped before save (Defect #1)")
                .isNotNull()
                .isEqualByComparingTo(amount);
    }

    /**
     * Defect #1: multi-line journal — totals aggregate across all debit/credit lines.
     * DR 300 + DR 200 = 500; CR 500 = 500.
     */
    @Test
    void post_multiLineJournal_stampsSummedTotals() {
        // Stub a third account for the two-debit variant
        Long acct3 = 300L;
        stubAccount(acct3, true);

        JournalEntryDraft draft = new JournalEntryDraft(
                COMPANY_ID, BRANCH_ID, LocalDate.now(),
                "multi-line totals", JournalSourceType.MANUAL, null, null, ACTOR_ID,
                List.of(
                        new JournalEntryDraft.LineDraft(
                                ACCT_DR, new BigDecimal("300"), null, "TZS", "dr1",
                                null, null, null, null, null, null, null),
                        new JournalEntryDraft.LineDraft(
                                acct3, new BigDecimal("200"), null, "TZS", "dr2",
                                null, null, null, null, null, null, null),
                        new JournalEntryDraft.LineDraft(
                                ACCT_CR, null, new BigDecimal("500"), "TZS", "cr1",
                                null, null, null, null, null, null, null)
                ));

        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        when(entries.save(entryCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.post(draft);

        JournalEntry saved = entryCaptor.getValue();
        assertThat(saved.getTotalDebit()).isEqualByComparingTo("500");
        assertThat(saved.getTotalCredit()).isEqualByComparingTo("500");
    }
}
