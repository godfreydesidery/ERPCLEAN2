package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.erp.modules.ap.domain.dto.SetApOpeningBalanceRequest;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.entity.SupplierBillLine;
import com.erp.modules.ap.domain.enums.SupplierBillSource;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.SupplierBillLineRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Supplier;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link ApOpeningBalanceServiceImpl} — UAT 2026-08-12.
 *
 * <p>An adversarial review of the AP 3-way-match hardening flagged this service as a second route to
 * a payable the match engine never inspects: it stamps a bill MATCHED and posts its own journal
 * entry with no {@code bill_match} rows. The review concluded the behaviour is <b>intentional</b> —
 * an opening balance is a pre-go-live liability with no purchase order and no goods receipt to
 * compare against, and MATCHED is what the ADR-0015 D-7 balance definition and D-8 reconciliation
 * invariant require. These tests exist so nobody "fixes" it into a hold, and so nobody lets it start
 * claiming a comparison happened.
 *
 * <p>What is pinned here:
 * <ol>
 *   <li><b>Legitimate opening balances keep working</b> — MATCHED, outstanding == gross, GL posted.
 *   <li><b>The debit leg is Opening Balance Equity, never Purchases</b> — this is the structural
 *       reason the path cannot be used to book an ordinary trade purchase's cost.
 *   <li><b>No fabricated match evidence</b> — {@code matched_at} / {@code matched_by} stay NULL, and
 *       the service holds no {@code BillMatchRepository}, so it cannot write a match row that would
 *       read as "this line was compared".
 *   <li><b>The honest state is recorded</b> — the append-only audit entry says outright that no
 *       three-way comparison was performed.
 *   <li><b>The synthetic line carries no PO/GR link</b> — so any "was this compared?" signal derived
 *       from match evidence can only ever answer "no", never "clean by omission".
 *   <li><b>The duplicate-payable guard still fires</b>.
 * </ol>
 */
class ApOpeningBalanceServiceImplTest {

    private SupplierBillRepository     bills;
    private SupplierBillLineRepository lines;
    private CompanyRepository          companies;
    private SupplierRepository         suppliers;
    private ApBillNumberGenerator      numbers;
    private GLPostingService           glPosting;
    private GLConfigResolver           glConfig;
    private ScopeGuard                 scopeGuard;
    private AuditService               audit;
    private ApOpeningBalanceServiceImpl service;

    private static final Long   COMPANY_ID   = 10L;
    private static final Long   BRANCH_ID    = 20L;
    private static final Long   ACTOR_ID     = 1L;
    private static final Long   SUPPLIER_ID  = 7L;
    private static final String COMPANY_UID  = "CO-TEST";
    private static final String SUPPLIER_UID = "SUP-TEST";

    private static final Long OBE_ACCOUNT_ID = 3000L;
    private static final Long AP_ACCOUNT_ID  = 2100L;

    @BeforeEach
    void setUp() {
        bills      = mock(SupplierBillRepository.class);
        lines      = mock(SupplierBillLineRepository.class);
        companies  = mock(CompanyRepository.class);
        suppliers  = mock(SupplierRepository.class);
        numbers    = mock(ApBillNumberGenerator.class);
        glPosting  = mock(GLPostingService.class);
        glConfig   = mock(GLConfigResolver.class);
        scopeGuard = mock(ScopeGuard.class);
        audit      = mock(AuditService.class);

        service = new ApOpeningBalanceServiceImpl(
                bills, lines, companies, suppliers, numbers,
                glPosting, glConfig, scopeGuard, audit);

        RequestContext.set(new RequestContext.Principal(
                ACTOR_ID, "clerk@test.com", false, COMPANY_ID, BRANCH_ID, null));

        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        when(company.getBaseCurrency()).thenReturn("TZS");
        when(companies.findByUid(COMPANY_UID)).thenReturn(Optional.of(company));
        when(companies.findById(COMPANY_ID)).thenReturn(Optional.of(company));

        Supplier supplier = mock(Supplier.class);
        when(supplier.getId()).thenReturn(SUPPLIER_ID);
        when(suppliers.findByCompanyIdAndUid(COMPANY_ID, SUPPLIER_UID))
                .thenReturn(Optional.of(supplier));

        when(numbers.nextBill(COMPANY_ID)).thenReturn("BILL-0001");

        ChartOfAccount obe = mock(ChartOfAccount.class);
        ChartOfAccount ap  = mock(ChartOfAccount.class);
        when(obe.getId()).thenReturn(OBE_ACCOUNT_ID);
        when(ap.getId()).thenReturn(AP_ACCOUNT_ID);
        when(glConfig.resolve(COMPANY_ID, GlConfigKey.OPENING_BALANCE_EQUITY)).thenReturn(obe);
        when(glConfig.resolve(COMPANY_ID, GlConfigKey.ACCOUNTS_PAYABLE)).thenReturn(ap);

        JournalEntryDto posted = mock(JournalEntryDto.class);
        when(posted.uid()).thenReturn("JE-OB-0001");
        when(glPosting.post(any())).thenReturn(posted);

        // save() hands back the very entity it was given, with a persisted id/uid stamped on first
        // call — so assertions below inspect the real state the service left on the row.
        when(bills.save(any(SupplierBill.class))).thenAnswer(inv -> {
            SupplierBill b = inv.getArgument(0);
            if (b.getId() == null) {
                ReflectionTestUtils.setField(b, "id", 55L, Long.class);
                ReflectionTestUtils.setField(b, "uid", "01BILLOBTEST0000000000000", String.class);
            }
            return b;
        });
        when(lines.save(any(SupplierBillLine.class))).thenAnswer(inv -> {
            SupplierBillLine l = inv.getArgument(0);
            if (l.getId() == null) {
                ReflectionTestUtils.setField(l, "id", 66L, Long.class);
                ReflectionTestUtils.setField(l, "uid", "01LINEOBTEST0000000000000", String.class);
            }
            return l;
        });
        when(lines.findBySupplierBillIdOrderByLineNo(anyLong())).thenAnswer(inv -> List.of());
        when(bills.existsByCompanyIdAndSupplierIdAndSupplierInvoiceNo(anyLong(), anyLong(), any()))
                .thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private static SetApOpeningBalanceRequest request(String invoiceNo) {
        return new SetApOpeningBalanceRequest(
                COMPANY_UID, SUPPLIER_UID,
                new BigDecimal("5000.00"), "TZS",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 3, 31),
                invoiceNo);
    }

    private SupplierBill capturedBill() {
        ArgumentCaptor<SupplierBill> captor = ArgumentCaptor.forClass(SupplierBill.class);
        verify(bills, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    // -------------------------------------------------------------------------
    // 1. The legitimate behaviour — do not "fix" this into a hold
    // -------------------------------------------------------------------------

    @Test
    void openingBalance_postsMatchedBill_soTheSubLedgerReconcilesToGl() {
        service.setOpeningBalance(request("OB-2026"));

        SupplierBill bill = capturedBill();
        assertThat(bill.getSource()).isEqualTo(SupplierBillSource.OPENING_BALANCE);
        // MATCHED is required by ADR-0015 D-7 (the balance definition counts only MATCHED and
        // beyond) and D-8 (Σ sub-ledger == GL 2100). DRAFT or HELD here would credit 2100 while
        // omitting the payable from the supplier balance.
        assertThat(bill.getStatus()).isEqualTo(SupplierBillStatus.MATCHED);
        assertThat(bill.getOutstandingAmount()).isEqualByComparingTo("5000.00");
        assertThat(bill.getPostedGlEntryUid()).isEqualTo("JE-OB-0001");
        // No purchase order can be claimed — the request has no field for one.
        assertThat(bill.getPurchaseOrderUid()).isNull();
    }

    @Test
    void openingBalance_debitsOpeningBalanceEquity_neverAnExpenseAccount() {
        service.setOpeningBalance(request("OB-2026"));

        ArgumentCaptor<JournalEntryDraft> captor =
                ArgumentCaptor.forClass(JournalEntryDraft.class);
        verify(glPosting).post(captor.capture());
        List<JournalEntryDraft.LineDraft> glLines = captor.getValue().lines();

        // This is the structural reason the path is not an equivalent of the match bypass: the cost
        // of a purchase can never reach the P&L or inventory through here. Abusing it inflates
        // equity, which a trial-balance review sees at once.
        assertThat(glLines).hasSize(2);
        assertThat(glLines.get(0).accountId()).isEqualTo(OBE_ACCOUNT_ID);
        assertThat(glLines.get(0).debitAmount()).isEqualByComparingTo("5000.00");
        assertThat(glLines.get(1).accountId()).isEqualTo(AP_ACCOUNT_ID);
        assertThat(glLines.get(1).creditAmount()).isEqualByComparingTo("5000.00");

        // Never resolves an expense/inventory account — it does not even ask for one.
        verify(glConfig).resolve(COMPANY_ID, GlConfigKey.OPENING_BALANCE_EQUITY);
        verify(glConfig).resolve(COMPANY_ID, GlConfigKey.ACCOUNTS_PAYABLE);
        verifyNoMoreInteractions(glConfig);
    }

    // -------------------------------------------------------------------------
    // 2. Honesty — the bill must not look like one that passed a three-way comparison
    // -------------------------------------------------------------------------

    @Test
    void openingBalance_doesNotStampMatchAudit_becauseNoComparisonRan() {
        service.setOpeningBalance(request("OB-2026"));

        SupplierBill bill = capturedBill();
        // matched_at / matched_by are the match engine's trail (the only other writer is
        // BillMatchServiceImpl.postMatchedBillToGl). Stamping them here named the opening-balance
        // clerk as the person who matched the bill — which never happened.
        assertThat(bill.getMatchedAt()).isNull();
        assertThat(bill.getMatchedBy()).isNull();
        // The entry is still fully attributable without inventing a match.
        assertThat(bill.getCreatedBy()).isEqualTo(ACTOR_ID);
        assertThat(bill.getPostedGlEntryUid()).isNotNull();
    }

    @Test
    void openingBalance_recordsThatNoThreeWayMatchWasPerformed() {
        service.setOpeningBalance(request("OB-2026"));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(captor.capture());
        AuditEvent event = captor.getValue();

        assertThat(event.action()).isEqualTo(AuditActions.AP_OPENING_SET);
        assertThat(event.targetType()).isEqualTo("supplier_bills");
        // audit_log is append-only, so this is the durable answer to "was this payable ever checked
        // against an order and a receipt?" for a bill that has no bill_match rows to answer it.
        assertThat(event.detail()).containsEntry("threeWayMatch", "NOT_PERFORMED");
        assertThat(event.detail().get("threeWayMatchReason").toString())
                .containsIgnoringCase("no purchase order or goods receipt");
    }

    @Test
    void openingBalance_lineCarriesNoPoOrGrLink_soNoSignalCanReadItAsCompared() {
        service.setOpeningBalance(request("OB-2026"));

        ArgumentCaptor<SupplierBillLine> captor = ArgumentCaptor.forClass(SupplierBillLine.class);
        verify(lines).save(captor.capture());
        SupplierBillLine line = captor.getValue();

        // A "was this bill compared?" signal derived from bill_match evidence must be able to answer
        // "never checked" for this line. It can: there is no PO line and no GR line to compare, and
        // therefore no match row will ever carry a po_unit_cost_amount / gr_received_qty for it.
        assertThat(line.getPoLineUid()).isNull();
        assertThat(line.getGrLineUid()).isNull();
        assertThat(line.getDescription()).startsWith("Opening balance");
    }

    // -------------------------------------------------------------------------
    // 3. The duplicate-payable guard
    // -------------------------------------------------------------------------

    @Test
    void openingBalance_duplicateSupplierInvoiceNo_isRejectedBeforeAnyGlPosting() {
        when(bills.existsByCompanyIdAndSupplierIdAndSupplierInvoiceNo(
                COMPANY_ID, SUPPLIER_ID, "OB-2026")).thenReturn(true);

        assertThatThrownBy(() -> service.setOpeningBalance(request("OB-2026")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been entered");

        verify(glPosting, org.mockito.Mockito.never()).post(any());
        verify(bills, org.mockito.Mockito.never()).save(any(SupplierBill.class));
    }

    @Test
    void openingBalance_blankInvoiceNo_fallsBackToOneOpeningBalancePerSupplier() {
        service.setOpeningBalance(request("  "));

        SupplierBill bill = capturedBill();
        // The fallback ref is what makes the duplicate guard bite for callers that supply none:
        // a second unnamed opening balance for the same supplier collides with the first.
        assertThat(bill.getSupplierInvoiceNo()).isEqualTo("OB-" + SUPPLIER_UID);
    }
}
