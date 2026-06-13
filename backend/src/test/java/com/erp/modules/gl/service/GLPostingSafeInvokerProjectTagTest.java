package com.erp.modules.gl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for GLPostingSafeInvoker — Finding #3: project tag threading onto the
 * CR Sales Revenue leg (ADR-0033 D-4c).
 *
 * <p>When an invoice is tagged to a project, the revenue GL leg must carry the project_id.
 * The cash/AR debit leg and VAT payable leg must remain untagged (D-4c constraint:
 * only P&amp;L-relevant legs carry the project tag).
 */
class GLPostingSafeInvokerProjectTagTest {

    private GLPostingService    postingService;
    private GLConfigResolver    configResolver;
    private GLPostingSafeInvoker invoker;

    @BeforeEach
    void setUp() {
        postingService  = mock(GLPostingService.class);
        configResolver  = mock(GLConfigResolver.class);
        invoker         = new GLPostingSafeInvoker(postingService, configResolver);
    }

    /**
     * Finding #3: the CR Sales Revenue line must have projectId and projectTaskId
     * when the invoice is tagged to a project.
     */
    @Test
    void postSaleInNewTx_projectTagged_revenueLineCarriesProjectId() {
        // Arrange
        ChartOfAccount cashAcct    = mockAccount(100L);
        ChartOfAccount revenueAcct = mockAccount(400L);
        ChartOfAccount vatAcct     = mockAccount(300L);

        when(configResolver.resolve(10L, GlConfigKey.CASH)).thenReturn(cashAcct);
        when(configResolver.resolve(10L, GlConfigKey.SALES_REVENUE)).thenReturn(revenueAcct);
        when(configResolver.resolve(10L, GlConfigKey.VAT_PAYABLE)).thenReturn(vatAcct);

        ArgumentCaptor<JournalEntryDraft> draftCaptor = ArgumentCaptor.forClass(JournalEntryDraft.class);
        JournalEntryDto posted = mock(JournalEntryDto.class);
        when(posted.uid()).thenReturn("je-1");
        when(postingService.post(draftCaptor.capture())).thenReturn(posted);

        BigDecimal gross = new BigDecimal("1180");
        BigDecimal net   = new BigDecimal("1000");
        BigDecimal vat   = new BigDecimal("180");
        Long projectId   = 99L;
        Long taskId      = 77L;

        // Act — project-tagged 13-arg overload
        invoker.postSaleInNewTx(10L, 20L, "inv-uid-1",
                "USD", gross, net, vat, true, LocalDate.now(),
                null, null, projectId, taskId);

        // Assert
        JournalEntryDraft draft = draftCaptor.getValue();
        List<LineDraft> lines = draft.lines();

        // Revenue leg: CR (creditAmount = net), must carry projectId
        LineDraft revenueLine = lines.stream()
                .filter(l -> l.accountId().equals(400L))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Revenue line not found"));

        assertThat(revenueLine.creditAmount())
                .isEqualByComparingTo(net);
        assertThat(revenueLine.projectId())
                .as("revenue leg must carry the invoice's project tag (ADR-0033 D-4c)")
                .isEqualTo(projectId);
        assertThat(revenueLine.projectTaskId())
                .as("revenue leg must carry the invoice's task tag")
                .isEqualTo(taskId);

        // Cash/AR debit leg: must NOT carry project tag (balance-sheet leg, untagged)
        LineDraft debitLine = lines.stream()
                .filter(l -> l.accountId().equals(100L))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Debit (Cash) line not found"));
        assertThat(debitLine.projectId())
                .as("Cash/AR leg must be untagged (balance-sheet control leg)")
                .isNull();

        // VAT leg: must NOT carry project tag
        LineDraft vatLine = lines.stream()
                .filter(l -> l.accountId().equals(300L))
                .findFirst()
                .orElseThrow(() -> new AssertionError("VAT line not found"));
        assertThat(vatLine.projectId())
                .as("VAT leg must be untagged (balance-sheet control leg)")
                .isNull();
    }

    @Test
    void postSaleInNewTx_untaggedInvoice_revenueLineHasNullProjectId() {
        // An invoice with no project tag must post null projectId on all lines (NFR-PROJ-04)
        ChartOfAccount cashAcct    = mockAccount(100L);
        ChartOfAccount revenueAcct = mockAccount(400L);
        ChartOfAccount vatAcct     = mockAccount(300L);

        when(configResolver.resolve(10L, GlConfigKey.CASH)).thenReturn(cashAcct);
        when(configResolver.resolve(10L, GlConfigKey.SALES_REVENUE)).thenReturn(revenueAcct);
        when(configResolver.resolve(10L, GlConfigKey.VAT_PAYABLE)).thenReturn(vatAcct);

        ArgumentCaptor<JournalEntryDraft> draftCaptor = ArgumentCaptor.forClass(JournalEntryDraft.class);
        JournalEntryDto posted = mock(JournalEntryDto.class);
        when(posted.uid()).thenReturn("je-2");
        when(postingService.post(draftCaptor.capture())).thenReturn(posted);

        // Legacy 11-arg overload (null projectId by backward compat)
        invoker.postSaleInNewTx(10L, 20L, "inv-uid-2",
                "USD", new BigDecimal("118"), new BigDecimal("100"), new BigDecimal("18"),
                true, LocalDate.now(), null, null);

        JournalEntryDraft draft = draftCaptor.getValue();
        LineDraft revenueLine = draft.lines().stream()
                .filter(l -> l.accountId().equals(400L))
                .findFirst().orElseThrow();

        assertThat(revenueLine.projectId())
                .as("untagged invoice must have null projectId (NFR-PROJ-04)")
                .isNull();
    }

    @Test
    void postSaleInNewTx_journalIsBalanced_withProjectTag() {
        // Balanced: DR gross == CR (net + vat)
        ChartOfAccount cashAcct    = mockAccount(100L);
        ChartOfAccount revenueAcct = mockAccount(400L);
        ChartOfAccount vatAcct     = mockAccount(300L);

        when(configResolver.resolve(10L, GlConfigKey.CASH)).thenReturn(cashAcct);
        when(configResolver.resolve(10L, GlConfigKey.SALES_REVENUE)).thenReturn(revenueAcct);
        when(configResolver.resolve(10L, GlConfigKey.VAT_PAYABLE)).thenReturn(vatAcct);

        ArgumentCaptor<JournalEntryDraft> draftCaptor = ArgumentCaptor.forClass(JournalEntryDraft.class);
        JournalEntryDto posted = mock(JournalEntryDto.class);
        when(posted.uid()).thenReturn("je-3");
        when(postingService.post(draftCaptor.capture())).thenReturn(posted);

        BigDecimal gross = new BigDecimal("1180");
        BigDecimal net   = new BigDecimal("1000");
        BigDecimal vat   = new BigDecimal("180");

        invoker.postSaleInNewTx(10L, 20L, "inv-uid-3",
                "USD", gross, net, vat, true, LocalDate.now(),
                null, null, 55L, null);

        JournalEntryDraft draft = draftCaptor.getValue();
        BigDecimal totalDebit = draft.lines().stream()
                .map(l -> l.debitAmount() != null ? l.debitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = draft.lines().stream()
                .map(l -> l.creditAmount() != null ? l.creditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(totalDebit)
                .as("journal entry with project tag must still be balanced (DR == CR)")
                .isEqualByComparingTo(totalCredit);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ChartOfAccount mockAccount(Long id) {
        ChartOfAccount a = mock(ChartOfAccount.class);
        when(a.getId()).thenReturn(id);
        return a;
    }
}
