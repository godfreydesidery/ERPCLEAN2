package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.ap.domain.dto.BillMatchResultDto;
import com.erp.modules.ap.domain.dto.BillMatchResultDto.LineMatchDto;
import com.erp.modules.ap.domain.entity.BillMatch;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.entity.SupplierBillLine;
import com.erp.modules.ap.domain.enums.BillMatchStatus;
import com.erp.modules.ap.domain.enums.SupplierBillSource;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.BillMatchRepository;
import com.erp.modules.ap.repository.SupplierBillLineRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.purchases.domain.dto.GoodsReceiptLineDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderLineDto;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.money.ConvertedAmount;
import com.erp.platform.common.money.FxDocumentConverter;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The AP 3-way match must fail CLOSED.
 *
 * <p>Live UAT: an accountant billed 15 × 9,999,999 (TZS 149,999,985) against a purchase order line
 * of 20 × 4,500 with 15 received. The match answered MATCHED with priceVariance 0, qtyVariance 0
 * and both poUnitCost and grReceivedQty NULL — no comparison had run at all — and the bill
 * auto-posted to the GL. The old code said so in as many words: "PO/GR not resolved — treat as
 * MATCHED (service bill or data gap)".
 *
 * <p>Everything here runs as a NON-root principal, because root bypasses the permission checks
 * that a real accountant goes through and would mask the very gap this suite guards.
 */
class BillMatchFailClosedTest {

    private static final Long COMPANY_ID = 10L;
    private static final Long BRANCH_ID  = 20L;
    private static final Long PURCHASES_ACCT = 500L;
    private static final Long GRNI_ACCT      = 510L;
    private static final Long AP_ACCT        = 200L;

    private SupplierBillRepository     billRepo;
    private SupplierBillLineRepository lineRepo;
    private BillMatchRepository        matchRepo;
    private PurchaseMatchReader        purchaseReader;
    private GLPostingService           glPosting;
    private GLConfigResolver           glConfig;
    private JournalEntryRepository     journalEntries;
    private JdbcTemplate               jdbc;
    private BillMatchServiceImpl       service;

    /** Live view of every bill_match row saved — also what the posting guard re-reads. */
    private final List<BillMatch> savedMatches = new ArrayList<>();

    @BeforeEach
    void setUp() {
        billRepo       = mock(SupplierBillRepository.class);
        lineRepo       = mock(SupplierBillLineRepository.class);
        matchRepo      = mock(BillMatchRepository.class);
        purchaseReader = mock(PurchaseMatchReader.class);
        glPosting      = mock(GLPostingService.class);
        glConfig       = mock(GLConfigResolver.class);
        journalEntries = mock(JournalEntryRepository.class);
        jdbc           = mock(JdbcTemplate.class);

        FxDocumentConverter fxConverter = mock(FxDocumentConverter.class);
        when(fxConverter.toBase(any(), any(), any(), any()))
                .thenAnswer(inv -> ConvertedAmount.identity(inv.getArgument(0)));
        when(fxConverter.balancingPlug(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> ((List<BigDecimal>) inv.getArgument(0)).stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add).negate());

        savedMatches.clear();
        when(matchRepo.findBySupplierBillLineId(any())).thenReturn(Optional.empty());
        when(matchRepo.save(any())).thenAnswer(inv -> {
            BillMatch m = inv.getArgument(0);
            savedMatches.add(m);
            return m;
        });
        when(matchRepo.findBySupplierBillId(any())).thenReturn(savedMatches);

        when(journalEntries.findByCompanyIdAndSourceTypeAndSourceRef(any(), any(), any()))
                .thenReturn(Optional.empty());
        // Build each account mock BEFORE it goes into a when(...) — stubbing a mock inside an
        // unfinished stubbing call is what Mockito flags as UnfinishedStubbing.
        ChartOfAccount purchases = account(PURCHASES_ACCT);
        ChartOfAccount grni      = account(GRNI_ACCT);
        ChartOfAccount ap        = account(AP_ACCT);
        when(glConfig.resolve(COMPANY_ID, GlConfigKey.PURCHASES)).thenReturn(purchases);
        when(glConfig.resolve(COMPANY_ID, GlConfigKey.GRNI)).thenReturn(grni);
        when(glConfig.resolve(COMPANY_ID, GlConfigKey.ACCOUNTS_PAYABLE)).thenReturn(ap);

        JournalEntryDto posted = mock(JournalEntryDto.class);
        when(posted.uid()).thenReturn("je-uid-1");
        when(glPosting.post(any(JournalEntryDraft.class))).thenReturn(posted);

        when(billRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new BillMatchServiceImpl(
                billRepo, lineRepo, matchRepo, purchaseReader,
                glPosting, glConfig, journalEntries,
                mock(ApBillNumberGenerator.class),
                mock(ScopeGuard.class), mock(AuditService.class), jdbc, fxConverter);

        // A clerk, not root: root short-circuits authorisation and hides real-world behaviour.
        RequestContext.set(new RequestContext.Principal(
                7L, "amina.accounts", false, COMPANY_ID, BRANCH_ID, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Fail closed — the control did not run
    // =========================================================================

    @Test
    @DisplayName("UAT case: 15 × 9,999,999 billed against a 20 × 4,500 order line with an "
            + "unresolvable goods receipt is HELD, not MATCHED, and does not post")
    void runMatch_goodsBillWithUnresolvableGoodsReceipt_holdsAndDoesNotPost() {
        SupplierBill bill = bill("po-uid-1", new BigDecimal("149999985"));
        SupplierBillLine line = line("pol-1", "grl-1",
                new BigDecimal("15"), new BigDecimal("9999999"));
        stubBill(bill, line);

        // The order line resolves at 4,500 — the goods receipt line does not (jdbc lookup returns
        // null, exactly as it does for a bill line whose receipt was never attached).
        when(purchaseReader.findPoLine("po-uid-1", "pol-1"))
                .thenReturn(Optional.of(poLine(new BigDecimal("4500"))));

        BillMatchResultDto result = service.runMatch("bill-uid-1");

        LineMatchDto lineResult = result.lineResults().get(0);
        assertThat(lineResult.matchStatus())
                .as("no comparison ran against a receipt — this must never come out MATCHED")
                .isNotEqualTo(BillMatchStatus.MATCHED)
                .isEqualTo(BillMatchStatus.HELD_PRICE_VARIANCE);
        assertThat(result.billStatus()).isEqualTo(SupplierBillStatus.HELD);
        assertThat(bill.getStatus()).isEqualTo(SupplierBillStatus.HELD);

        assertThat(lineResult.comparisonPerformed())
                .as("the three-way comparison did not complete")
                .isFalse();
        assertThat(lineResult.grReceivedQty()).isNull();
        assertThat(lineResult.qtyVariance())
                .as("nothing was compared on quantity — a 0 here would read as 'checked and equal'")
                .isNull();
        // The leg that COULD run is still reported, so the reviewer sees the real numbers.
        assertThat(lineResult.poUnitCostAmount()).isEqualByComparingTo("4500");
        assertThat(lineResult.priceVarianceAmount()).isEqualByComparingTo("9995499");

        assertThat(lineResult.matchNote())
                .as("must tell the accountant what to do, in plain English")
                .contains("goods receipt")
                .contains("run the match again")
                .doesNotContain("null");

        verify(glPosting, never()).post(any());
        assertThat(bill.getPostedGlEntryUid()).isNull();

        BillMatch persisted = savedMatches.get(0);
        assertThat(persisted.getMatchStatus()).isEqualTo(BillMatchStatus.HELD_PRICE_VARIANCE);
        assertThat(persisted.getGrReceivedQty())
                .as("a NULL fact on the row is how a reviewer knows the check never ran")
                .isNull();
        assertThat(persisted.getVarianceReason()).isNotBlank();
    }

    @Test
    @DisplayName("A bill raised against a purchase order whose line links nothing is HELD")
    void runMatch_billOnPurchaseOrderWithNoLineLinks_holdsAndDoesNotPost() {
        SupplierBill bill = bill("po-uid-1", new BigDecimal("67500"));
        SupplierBillLine line = line(null, null, new BigDecimal("15"), new BigDecimal("4500"));
        stubBill(bill, line);

        BillMatchResultDto result = service.runMatch("bill-uid-1");

        LineMatchDto lineResult = result.lineResults().get(0);
        assertThat(lineResult.matchStatus()).isNotEqualTo(BillMatchStatus.MATCHED);
        assertThat(result.billStatus()).isEqualTo(SupplierBillStatus.HELD);
        assertThat(lineResult.comparisonPerformed()).isFalse();
        assertThat(lineResult.poUnitCostAmount()).isNull();
        assertThat(lineResult.grReceivedQty()).isNull();
        assertThat(lineResult.priceVarianceAmount()).isNull();
        assertThat(lineResult.qtyVariance()).isNull();
        assertThat(lineResult.matchNote())
                .contains("purchase order line")
                .contains("goods receipt line");

        verify(glPosting, never()).post(any());
    }

    // =========================================================================
    // The legitimate service bill still passes — but says it was not compared
    // =========================================================================

    @Test
    @DisplayName("A genuine service bill with no purchase order or receipt still passes, "
            + "and is visibly distinguishable from a real match")
    void runMatch_serviceBillWithNoPurchaseRefs_matchesButIsMarkedNotCompared() {
        SupplierBill bill = bill(null, new BigDecimal("500"));
        SupplierBillLine line = line(null, null, new BigDecimal("1"), new BigDecimal("500"));
        stubBill(bill, line);

        BillMatchResultDto result = service.runMatch("bill-uid-1");

        LineMatchDto lineResult = result.lineResults().get(0);
        assertThat(result.billStatus()).isEqualTo(SupplierBillStatus.MATCHED);
        assertThat(lineResult.matchStatus()).isEqualTo(BillMatchStatus.MATCHED);
        assertThat(lineResult.comparisonPerformed())
                .as("nothing was compared — the reviewer must not read this as a passed 3-way match")
                .isFalse();
        assertThat(lineResult.priceVarianceAmount()).isNull();
        assertThat(lineResult.priceVariancePct()).isNull();
        assertThat(lineResult.qtyVariance()).isNull();
        assertThat(lineResult.matchNote()).contains("service charge");

        verify(glPosting).post(any(JournalEntryDraft.class));
        assertThat(bill.getPostedGlEntryUid()).isEqualTo("je-uid-1");
    }

    // =========================================================================
    // Happy path and the two real variances — unchanged behaviour
    // =========================================================================

    @Test
    @DisplayName("A real 3-way match within tolerance still MATCHES and posts")
    void runMatch_resolvedWithinTolerance_matchesAndPosts() {
        SupplierBill bill = bill("po-uid-1", new BigDecimal("67500"));
        SupplierBillLine line = line("pol-1", "grl-1",
                new BigDecimal("15"), new BigDecimal("4500"));
        stubBill(bill, line);
        stubResolvedPurchase(new BigDecimal("4500"), new BigDecimal("15"));

        BillMatchResultDto result = service.runMatch("bill-uid-1");

        LineMatchDto lineResult = result.lineResults().get(0);
        assertThat(result.billStatus()).isEqualTo(SupplierBillStatus.MATCHED);
        assertThat(lineResult.matchStatus()).isEqualTo(BillMatchStatus.MATCHED);
        assertThat(lineResult.comparisonPerformed()).isTrue();
        assertThat(lineResult.priceVarianceAmount()).isEqualByComparingTo("0");
        assertThat(lineResult.qtyVariance()).isEqualByComparingTo("0");
        assertThat(lineResult.poUnitCostAmount()).isEqualByComparingTo("4500");
        assertThat(lineResult.grReceivedQty()).isEqualByComparingTo("15");

        verify(glPosting).post(any(JournalEntryDraft.class));
        assertThat(bill.getPostedGlEntryUid()).isEqualTo("je-uid-1");
    }

    @Test
    @DisplayName("A price above tolerance still HOLDS")
    void runMatch_priceOverTolerance_holds() {
        SupplierBill bill = bill("po-uid-1", new BigDecimal("75000"));
        SupplierBillLine line = line("pol-1", "grl-1",
                new BigDecimal("15"), new BigDecimal("5000"));
        stubBill(bill, line);
        stubResolvedPurchase(new BigDecimal("4500"), new BigDecimal("15"));

        BillMatchResultDto result = service.runMatch("bill-uid-1");

        LineMatchDto lineResult = result.lineResults().get(0);
        assertThat(result.billStatus()).isEqualTo(SupplierBillStatus.HELD);
        assertThat(lineResult.matchStatus()).isEqualTo(BillMatchStatus.HELD_PRICE_VARIANCE);
        assertThat(lineResult.comparisonPerformed())
                .as("the comparison DID run here — the price is simply wrong")
                .isTrue();
        assertThat(lineResult.priceVarianceAmount()).isEqualByComparingTo("500");
        verify(glPosting, never()).post(any());
    }

    @Test
    @DisplayName("Billing more than was received still HOLDS")
    void runMatch_overBilledQty_holds() {
        SupplierBill bill = bill("po-uid-1", new BigDecimal("67500"));
        SupplierBillLine line = line("pol-1", "grl-1",
                new BigDecimal("15"), new BigDecimal("4500"));
        stubBill(bill, line);
        stubResolvedPurchase(new BigDecimal("4500"), new BigDecimal("10"));

        BillMatchResultDto result = service.runMatch("bill-uid-1");

        LineMatchDto lineResult = result.lineResults().get(0);
        assertThat(result.billStatus()).isEqualTo(SupplierBillStatus.HELD);
        assertThat(lineResult.matchStatus()).isEqualTo(BillMatchStatus.HELD_QTY_VARIANCE);
        assertThat(lineResult.comparisonPerformed()).isTrue();
        assertThat(lineResult.qtyVariance()).isEqualByComparingTo("5");
        verify(glPosting, never()).post(any());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private SupplierBill bill(String purchaseOrderUid, BigDecimal gross) {
        return new SupplierBill(
                COMPANY_ID, BRANCH_ID, 5L, "SI-9001", SupplierBillSource.BILL, purchaseOrderUid,
                LocalDate.now(), LocalDate.now().plusDays(30),
                gross, BigDecimal.ZERO, gross, "TZS", 1L);
    }

    private SupplierBillLine line(String poLineUid, String grLineUid,
                                  BigDecimal qty, BigDecimal unitCost) {
        return new SupplierBillLine(null, COMPANY_ID, BRANCH_ID, (short) 1, null,
                poLineUid, grLineUid, "Cement 50kg", qty, unitCost, "TZS", 1L);
    }

    private void stubBill(SupplierBill bill, SupplierBillLine... billLines) {
        when(billRepo.findByUid("bill-uid-1")).thenReturn(Optional.of(bill));
        when(lineRepo.findBySupplierBillIdOrderByLineNo(any())).thenReturn(List.of(billLines));
    }

    /** Both sides of the 3-way resolve: the PO line at {@code unitCost}, the GR line at {@code received}. */
    private void stubResolvedPurchase(BigDecimal unitCost, BigDecimal received) {
        when(purchaseReader.findPoLine("po-uid-1", "pol-1"))
                .thenReturn(Optional.of(poLine(unitCost)));
        when(jdbc.queryForObject(startsWith("SELECT gr.uid"), eq(String.class),
                eq("grl-1"), eq(COMPANY_ID)))
                .thenReturn("gr-uid-1");
        when(purchaseReader.findGrLine("gr-uid-1", "grl-1"))
                .thenReturn(Optional.of(grLine(received)));
    }

    private static PurchaseOrderLineDto poLine(BigDecimal unitCost) {
        return new PurchaseOrderLineDto(
                1L, "pol-1", 1L, (short) 1, 99L, "CEM50", "Cement 50kg", 1L, "BAG",
                new BigDecimal("20"), new BigDecimal("20"), new BigDecimal("15"),
                BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.now(),
                new BigDecimal("5"), false,
                unitCost, unitCost.multiply(new BigDecimal("20")), "TZS", null, null);
    }

    private static GoodsReceiptLineDto grLine(BigDecimal received) {
        return new GoodsReceiptLineDto(
                1L, "grl-1", 1L, 1L, (short) 1, 99L, "CEM50", "Cement 50kg", 1L, "BAG",
                received, received, new BigDecimal("4500"),
                received.multiply(new BigDecimal("4500")), "TZS",
                null, null, null, List.of());
    }

    private static ChartOfAccount account(Long id) {
        ChartOfAccount a = mock(ChartOfAccount.class);
        when(a.getId()).thenReturn(id);
        return a;
    }
}
