package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.ap.domain.dto.BillLineRequest;
import com.erp.modules.ap.domain.dto.EnterBillRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.enums.DirectReceiptRatificationState;
import com.erp.modules.ap.domain.enums.SupplierBillSource;
import com.erp.modules.ap.domain.entity.SupplierBillLine;
import com.erp.modules.ap.repository.SupplierBillLineRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Supplier;
import com.erp.modules.parties.repository.PaymentTermsRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.purchases.domain.dto.PurchaseOrderApprovalSnapshotDto;
import com.erp.modules.purchases.domain.enums.PoApprovalStatus;
import com.erp.modules.purchases.domain.enums.PurchaseOrderOrigin;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Unit tests for SupplierBillServiceImpl guarding issue #18:
 * - dueDate before billDate must throw IllegalArgumentException (clean 400, not a DB 500).
 */
class SupplierBillServiceImplTest {

    private SupplierBillRepository     bills;
    private SupplierBillLineRepository lines;
    private SupplierRepository         suppliers;
    private PaymentTermsRepository     paymentTermsRepo;
    private CompanyRepository          companies;
    private ChartOfAccountRepository   chartOfAccounts;
    private PurchaseMatchReader        purchaseMatchReader;
    private ScopeGuard                 scopeGuard;
    private AuditService               audit;
    private SupplierBillServiceImpl    service;

    private static final Long   COMPANY_ID  = 10L;
    private static final Long   SUPPLIER_ID = 30L;
    private static final String COMPANY_UID = "CO-TEST";
    private static final String SUPP_UID    = "SUP-TEST";
    private static final String PO_UID      = "PO-UID-1";

    @BeforeEach
    void setUp() {
        bills               = mock(SupplierBillRepository.class);
        lines               = mock(SupplierBillLineRepository.class);
        suppliers           = mock(SupplierRepository.class);
        paymentTermsRepo    = mock(PaymentTermsRepository.class);
        companies           = mock(CompanyRepository.class);
        chartOfAccounts     = mock(ChartOfAccountRepository.class);
        purchaseMatchReader = mock(PurchaseMatchReader.class);
        scopeGuard          = mock(ScopeGuard.class);
        audit               = mock(AuditService.class);

        service = new SupplierBillServiceImpl(
                bills, lines, suppliers, paymentTermsRepo,
                companies, chartOfAccounts, purchaseMatchReader,
                new DirectReceiptRatificationGuard(purchaseMatchReader),
                scopeGuard, audit);

        RequestContext.set(new RequestContext.Principal(
                1L, "user@test.com", false, COMPANY_ID, 20L, null));

        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        when(company.getBaseCurrency()).thenReturn("TZS");
        when(companies.findByUid(COMPANY_UID)).thenReturn(Optional.of(company));
        when(companies.findById(COMPANY_ID)).thenReturn(Optional.of(company));

        Supplier supplier = mock(Supplier.class);
        when(supplier.getId()).thenReturn(SUPPLIER_ID);
        when(supplier.getPaymentTermsId()).thenReturn(null);
        when(supplier.getPaymentTermsDays()).thenReturn(null);
        when(suppliers.findByCompanyIdAndUid(COMPANY_ID, SUPP_UID))
                .thenReturn(Optional.of(supplier));

        when(bills.existsByCompanyIdAndSupplierIdAndSupplierInvoiceNo(
                anyLong(), anyLong(), anyString())).thenReturn(false);
        when(bills.save(any(SupplierBill.class))).thenAnswer(inv -> inv.getArgument(0));
        when(lines.save(any(SupplierBillLine.class))).thenAnswer(inv -> inv.getArgument(0));
        // List reads fetch each bill's lines; unsaved fixtures have a null id, hence nullable().
        when(lines.findBySupplierBillIdOrderByLineNo(nullable(Long.class))).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Issue #18 — dueDate before billDate
    // -------------------------------------------------------------------------

    @Test
    void enterBill_dueDateBeforeBillDate_throwsIllegalArgument() {
        LocalDate billDate = LocalDate.of(2026, 6, 18);
        LocalDate dueDate  = LocalDate.of(2020, 1, 1);   // clearly before

        EnterBillRequest req = validBillRequest(billDate, dueDate);

        assertThatThrownBy(() -> service.enterBill(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dueDate")
                .hasMessageContaining("billDate");
    }

    @Test
    void enterBill_dueDateEqualsBillDate_accepted() {
        LocalDate date = LocalDate.of(2026, 6, 18);

        EnterBillRequest req = validBillRequest(date, date);

        // Must not throw — same-day due is valid
        service.enterBill(req);
    }

    @Test
    void enterBill_dueDateAfterBillDate_accepted() {
        LocalDate billDate = LocalDate.of(2026, 6, 18);
        LocalDate dueDate  = LocalDate.of(2026, 7, 18);

        EnterBillRequest req = validBillRequest(billDate, dueDate);

        // Must not throw
        service.enterBill(req);
    }

    @Test
    void enterBill_dueDateNull_derivedByService_accepted() {
        // When caller omits dueDate, service derives it (net-on-receipt = billDate when no terms).
        EnterBillRequest req = validBillRequest(LocalDate.of(2026, 6, 18), null);

        // Must not throw — derived dueDate will equal billDate
        service.enterBill(req);
    }

    // -------------------------------------------------------------------------
    // K3 follow-up — the direct-receipt ratification state is visible on the bill
    // -------------------------------------------------------------------------

    @Test
    void enterBill_againstUnratifiedDirectReceipt_isAllowedButFlagged() {
        // Entry is deliberately NOT blocked: the supplier invoice is a real document that arrived,
        // and a payable you refuse to record is invisible. The bill carries the state instead, so
        // the payment-time refusal is never a surprise.
        givenBackingOrder(PurchaseOrderOrigin.DIRECT_RECEIPT, "PENDING");

        SupplierBillDto dto = service.enterBill(billRequestFor(PO_UID));

        assertThat(dto.directReceiptRatification())
                .isEqualTo(DirectReceiptRatificationState.AWAITING_RATIFICATION);
    }

    @Test
    void enterBill_againstRatifiedDirectReceipt_isFlaggedRatified() {
        givenBackingOrder(PurchaseOrderOrigin.DIRECT_RECEIPT, "APPROVED");

        SupplierBillDto dto = service.enterBill(billRequestFor(PO_UID));

        assertThat(dto.directReceiptRatification())
                .isEqualTo(DirectReceiptRatificationState.RATIFIED);
    }

    @Test
    void enterBill_againstManualPo_isNotFlagged() {
        givenBackingOrder(PurchaseOrderOrigin.MANUAL, "PENDING");

        SupplierBillDto dto = service.enterBill(billRequestFor(PO_UID));

        assertThat(dto.directReceiptRatification())
                .isEqualTo(DirectReceiptRatificationState.NOT_APPLICABLE);
    }

    @Test
    void enterBill_withNoPurchaseOrder_isNotFlagged() {
        SupplierBillDto dto = service.enterBill(
                validBillRequest(LocalDate.of(2026, 6, 18), null));

        assertThat(dto.directReceiptRatification())
                .isEqualTo(DirectReceiptRatificationState.NOT_APPLICABLE);
    }

    // -------------------------------------------------------------------------
    // K3 follow-up — the bill LIST is a pure read, and reads the page in one call
    // -------------------------------------------------------------------------

    @Test
    void listByCompany_neverInvokesTheReconcilingPurchaseOrderRead() {
        // PurchaseMatchReader#findPo is the Purchases DETAIL read: it polls the approval engine,
        // mutates the order and records an audit row. listByCompany is @Transactional(readOnly =
        // true), where Hibernate flushes MANUALLY — so that write is liable to be discarded without
        // a sound, and the listing pays an engine round-trip per row for nothing. A listing must
        // read, not write.
        givenPage(bill("INV-L1", PO_UID));
        givenSnapshots(Map.of(PO_UID, snapshot(PO_UID, PurchaseOrderOrigin.DIRECT_RECEIPT,
                PoApprovalStatus.PENDING)));

        Page<SupplierBillDto> page = service.listByCompany(COMPANY_ID, PageRequest.of(0, 20));

        assertThat(page.getContent()).singleElement()
                .extracting(SupplierBillDto::directReceiptRatification)
                .isEqualTo(DirectReceiptRatificationState.AWAITING_RATIFICATION);
        verify(purchaseMatchReader, never()).findPo(anyString());
    }

    @Test
    @SuppressWarnings("unchecked") // ArgumentCaptor cannot express Collection<String> generically
    void listByCompany_resolvesTheWholePageInOneCall_deduplicatingSharedOrders() {
        // One call per page, not per bill: several deliveries against the same direct receipt are
        // ordinary, and each extra call is another approval-engine round-trip.
        String otherPoUid = "PO-UID-2";
        givenPage(bill("INV-L1", PO_UID),
                  bill("INV-L2", PO_UID),
                  bill("INV-L3", otherPoUid),
                  bill("INV-L4", null));
        givenSnapshots(Map.of(
                PO_UID, snapshot(PO_UID, PurchaseOrderOrigin.DIRECT_RECEIPT, PoApprovalStatus.PENDING),
                otherPoUid, snapshot(otherPoUid, PurchaseOrderOrigin.DIRECT_RECEIPT,
                        PoApprovalStatus.APPROVED)));

        Page<SupplierBillDto> page = service.listByCompany(COMPANY_ID, PageRequest.of(0, 20));

        ArgumentCaptor<Collection<String>> uids = ArgumentCaptor.forClass(Collection.class);
        verify(purchaseMatchReader, times(1)).findApprovalSnapshots(uids.capture());
        assertThat(uids.getValue()).containsExactlyInAnyOrder(PO_UID, otherPoUid);

        assertThat(page.getContent()).extracting(SupplierBillDto::directReceiptRatification)
                .containsExactly(
                        DirectReceiptRatificationState.AWAITING_RATIFICATION,
                        DirectReceiptRatificationState.AWAITING_RATIFICATION,
                        DirectReceiptRatificationState.RATIFIED,
                        DirectReceiptRatificationState.NOT_APPLICABLE);
    }

    @Test
    void listBySupplier_readsThePageTheSameWay() {
        SupplierBill b = bill("INV-L1", PO_UID);
        when(bills.findByCompanyIdAndSupplierId(eq(COMPANY_ID), eq(SUPPLIER_ID), any()))
                .thenReturn(new PageImpl<>(List.of(b)));
        givenSnapshots(Map.of(PO_UID, snapshot(PO_UID, PurchaseOrderOrigin.DIRECT_RECEIPT,
                PoApprovalStatus.REJECTED)));

        Page<SupplierBillDto> page =
                service.listBySupplier(COMPANY_ID, SUPPLIER_ID, PageRequest.of(0, 20));

        assertThat(page.getContent()).singleElement()
                .extracting(SupplierBillDto::directReceiptRatification)
                .isEqualTo(DirectReceiptRatificationState.RATIFICATION_REFUSED);
        verify(purchaseMatchReader, times(1)).findApprovalSnapshots(any());
        verify(purchaseMatchReader, never()).findPo(anyString());
    }

    @Test
    void listByCompany_stillRendersWhenTheOrderLookupCannotAnswer() {
        // Fail-open: a list must never 500 because a cross-module lookup hiccuped. The bill loses
        // its badge, not the AP clerk their screen.
        givenPage(bill("INV-L1", PO_UID));
        givenSnapshots(Map.of());

        Page<SupplierBillDto> page = service.listByCompany(COMPANY_ID, PageRequest.of(0, 20));

        assertThat(page.getContent()).singleElement()
                .extracting(SupplierBillDto::directReceiptRatification)
                .isEqualTo(DirectReceiptRatificationState.NOT_APPLICABLE);
    }

    @Test
    void listByCompany_pageWithNoBackingOrders_readsPurchasesNotAtAll() {
        givenPage(bill("INV-L1", null), bill("INV-L2", "   "));

        Page<SupplierBillDto> page = service.listByCompany(COMPANY_ID, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(SupplierBillDto::directReceiptRatification)
                .containsOnly(DirectReceiptRatificationState.NOT_APPLICABLE);
        verify(purchaseMatchReader, never()).findApprovalSnapshots(any());
        verify(purchaseMatchReader, never()).findPo(anyString());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void givenPage(SupplierBill... pageBills) {
        when(bills.findByCompanyId(eq(COMPANY_ID), any()))
                .thenReturn(new PageImpl<>(List.of(pageBills)));
    }

    private void givenSnapshots(Map<String, PurchaseOrderApprovalSnapshotDto> snapshots) {
        when(purchaseMatchReader.findApprovalSnapshots(any())).thenReturn(snapshots);
    }

    private static PurchaseOrderApprovalSnapshotDto snapshot(String uid, PurchaseOrderOrigin origin,
                                                             PoApprovalStatus approvalStatus) {
        return new PurchaseOrderApprovalSnapshotDto(uid, COMPANY_ID, origin, approvalStatus);
    }

    private static SupplierBill bill(String invoiceNo, String purchaseOrderUid) {
        return new SupplierBill(
                COMPANY_ID, 20L, SUPPLIER_ID, invoiceNo, SupplierBillSource.BILL,
                purchaseOrderUid, LocalDate.of(2026, 6, 18), LocalDate.of(2026, 7, 18),
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"), "TZS", 1L);
    }

    private void givenBackingOrder(PurchaseOrderOrigin origin, String approvalStatus) {
        when(purchaseMatchReader.findPo(PO_UID)).thenReturn(
                Optional.of(DirectReceiptRatificationGuardTest.po(origin, approvalStatus)));
    }

    private EnterBillRequest billRequestFor(String purchaseOrderUid) {
        BillLineRequest line = new BillLineRequest(
                null, null, null, "Test item",
                new BigDecimal("1"), new BigDecimal("100.00"));
        return new EnterBillRequest(
                COMPANY_UID, SUPP_UID, "INV-GUARD-002", purchaseOrderUid,
                LocalDate.of(2026, 6, 18), null,
                BigDecimal.ZERO, "TZS", null, List.of(line));
    }

    private EnterBillRequest validBillRequest(LocalDate billDate, LocalDate dueDate) {
        BillLineRequest line = new BillLineRequest(
                null, null, null, "Test item",
                new BigDecimal("1"), new BigDecimal("100.00"));
        return new EnterBillRequest(
                COMPANY_UID, SUPP_UID, "INV-GUARD-001", null,
                billDate, dueDate,
                BigDecimal.ZERO, "TZS", null, List.of(line));
    }
}
