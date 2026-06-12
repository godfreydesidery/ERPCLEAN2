package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.entity.SupplierBillLine;
import com.erp.modules.ap.domain.enums.SupplierBillSource;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.BillMatchRepository;
import com.erp.modules.ap.repository.SupplierBillLineRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit test for BillMatchServiceImpl — Finding #2: per-line project tag threading.
 *
 * <p>ADR-0033 D-4(b): each service bill line's {@code project_id} must be threaded onto
 * its own DR PURCHASES LineDraft. A multi-project bill must produce separate GL lines per
 * project — not one aggregated line that loses the per-line attribution.
 */
class BillMatchProjectTagTest {

    private SupplierBillRepository     billRepo;
    private SupplierBillLineRepository lineRepo;
    private BillMatchRepository        matchRepo;
    private GLPostingService           glPosting;
    private GLConfigResolver           glConfig;
    private JournalEntryRepository     journalEntries;
    private ScopeGuard                 scopeGuard;
    private AuditService               audit;
    private JdbcTemplate               jdbc;
    private BillMatchServiceImpl       service;

    @BeforeEach
    void setUp() {
        billRepo       = mock(SupplierBillRepository.class);
        lineRepo       = mock(SupplierBillLineRepository.class);
        matchRepo      = mock(BillMatchRepository.class);
        glPosting      = mock(GLPostingService.class);
        glConfig       = mock(GLConfigResolver.class);
        journalEntries = mock(JournalEntryRepository.class);
        scopeGuard     = mock(ScopeGuard.class);
        audit          = mock(AuditService.class);
        jdbc           = mock(JdbcTemplate.class);

        service = new BillMatchServiceImpl(
                billRepo, lineRepo, matchRepo,
                mock(PurchaseMatchReader.class),
                glPosting, glConfig, journalEntries,
                mock(ApBillNumberGenerator.class),
                scopeGuard, audit, jdbc);
    }

    /**
     * Finding #2: a service bill with two lines tagged to different projects must produce
     * two separate DR PURCHASES LineDrafts — one per line — each carrying its own project_id.
     * Before the fix, both lines were aggregated into one LineDraft losing per-line attribution.
     */
    @Test
    void runMatch_multiProjectServiceBill_producesPerLineLineDrafts() {
        // Arrange: bill with two service lines, each tagged to a different project
        SupplierBill bill = new SupplierBill(
                10L, 20L, 5L, "INV-001", SupplierBillSource.BILL, null,
                LocalDate.now(), LocalDate.now().plusDays(30),
                new BigDecimal("300"), BigDecimal.ZERO, new BigDecimal("300"),
                "USD", 1L);
        // Set status to DRAFT via reflection (UidEntity id is unset but test only needs bill data)
        when(billRepo.findByUid("bill-uid-1")).thenReturn(Optional.of(bill));

        // Two service lines (grLineUid = null), tagged to different projects
        SupplierBillLine line1 = new SupplierBillLine(
                null, 10L, 20L, (short)1, null, null, null,
                "Subcontract Project A", new BigDecimal("1"), new BigDecimal("100"), "USD", 1L);
        line1.setProjectId(101L);
        line1.setProjectTaskId(201L);

        SupplierBillLine line2 = new SupplierBillLine(
                null, 10L, 20L, (short)2, null, null, null,
                "Subcontract Project B", new BigDecimal("1"), new BigDecimal("200"), "USD", 1L);
        line2.setProjectId(102L);
        line2.setProjectTaskId(null);

        when(lineRepo.findBySupplierBillIdOrderByLineNo(any())).thenReturn(List.of(line1, line2));
        when(matchRepo.findBySupplierBillLineId(any())).thenReturn(Optional.empty());
        when(matchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // No existing GL entry (idempotency guard passes)
        when(journalEntries.findByCompanyIdAndSourceTypeAndSourceRef(any(), any(), any()))
                .thenReturn(Optional.empty());

        // GL config resolves accounts
        ChartOfAccount purchasesAcct = mockAccount(500L);
        ChartOfAccount apAcct        = mockAccount(200L);
        when(glConfig.resolve(10L, GlConfigKey.PURCHASES)).thenReturn(purchasesAcct);
        when(glConfig.resolve(10L, GlConfigKey.ACCOUNTS_PAYABLE)).thenReturn(apAcct);

        // Capture the JournalEntryDraft posted to GL
        ArgumentCaptor<JournalEntryDraft> draftCaptor = ArgumentCaptor.forClass(JournalEntryDraft.class);
        JournalEntryDto posted = mock(JournalEntryDto.class);
        when(posted.uid()).thenReturn("je-uid-1");
        when(glPosting.post(draftCaptor.capture())).thenReturn(posted);

        RequestContext.set(new RequestContext.Principal(1L, "user", false, 10L, 20L, null));
        when(billRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        service.runMatch("bill-uid-1");

        // Assert: the journal draft must contain 2 PURCHASES lines (not 1 aggregated)
        JournalEntryDraft draft = draftCaptor.getValue();
        List<LineDraft> purchaseLines = draft.lines().stream()
                .filter(l -> l.accountId().equals(500L))
                .toList();

        assertThat(purchaseLines)
                .as("must have one LineDraft per service line (2 lines → 2 drafts), not aggregated")
                .hasSize(2);

        // Verify per-line project attribution
        LineDraft draftLine1 = purchaseLines.stream()
                .filter(l -> l.debitAmount().compareTo(new BigDecimal("100")) == 0)
                .findFirst().orElseThrow();
        assertThat(draftLine1.projectId())
                .as("line 1 must carry project 101")
                .isEqualTo(101L);
        assertThat(draftLine1.projectTaskId())
                .as("line 1 must carry task 201")
                .isEqualTo(201L);

        LineDraft draftLine2 = purchaseLines.stream()
                .filter(l -> l.debitAmount().compareTo(new BigDecimal("200")) == 0)
                .findFirst().orElseThrow();
        assertThat(draftLine2.projectId())
                .as("line 2 must carry project 102")
                .isEqualTo(102L);
        assertThat(draftLine2.projectTaskId())
                .as("line 2 task must be null (untagged at task level)")
                .isNull();

        RequestContext.clear();
    }

    @Test
    void runMatch_untaggedServiceBill_producesLineDraftWithNullProject() {
        // A service bill with no project tag must still produce a LineDraft with null projectId
        SupplierBill bill = new SupplierBill(
                10L, 20L, 5L, "INV-002", SupplierBillSource.BILL, null,
                LocalDate.now(), LocalDate.now().plusDays(30),
                new BigDecimal("150"), BigDecimal.ZERO, new BigDecimal("150"),
                "USD", 1L);
        when(billRepo.findByUid("bill-uid-2")).thenReturn(Optional.of(bill));

        SupplierBillLine line = new SupplierBillLine(
                null, 10L, 20L, (short)1, null, null, null,
                "Untagged service", new BigDecimal("1"), new BigDecimal("150"), "USD", 1L);
        // no setProjectId — remains null

        when(lineRepo.findBySupplierBillIdOrderByLineNo(any())).thenReturn(List.of(line));
        when(matchRepo.findBySupplierBillLineId(any())).thenReturn(Optional.empty());
        when(matchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(journalEntries.findByCompanyIdAndSourceTypeAndSourceRef(any(), any(), any()))
                .thenReturn(Optional.empty());

        ChartOfAccount purchasesAcct = mockAccount(500L);
        ChartOfAccount apAcct        = mockAccount(200L);
        when(glConfig.resolve(10L, GlConfigKey.PURCHASES)).thenReturn(purchasesAcct);
        when(glConfig.resolve(10L, GlConfigKey.ACCOUNTS_PAYABLE)).thenReturn(apAcct);

        ArgumentCaptor<JournalEntryDraft> draftCaptor = ArgumentCaptor.forClass(JournalEntryDraft.class);
        JournalEntryDto posted = mock(JournalEntryDto.class);
        when(posted.uid()).thenReturn("je-uid-2");
        when(glPosting.post(draftCaptor.capture())).thenReturn(posted);

        RequestContext.set(new RequestContext.Principal(1L, "user", false, 10L, 20L, null));
        when(billRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.runMatch("bill-uid-2");

        JournalEntryDraft draft = draftCaptor.getValue();
        List<LineDraft> purchaseLines = draft.lines().stream()
                .filter(l -> l.accountId().equals(500L))
                .toList();
        assertThat(purchaseLines).hasSize(1);
        assertThat(purchaseLines.get(0).projectId())
                .as("untagged line must have null projectId (NFR-PROJ-04)")
                .isNull();

        RequestContext.clear();
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
