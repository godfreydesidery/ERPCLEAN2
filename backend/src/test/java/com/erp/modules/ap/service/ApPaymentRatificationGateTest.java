package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.ap.domain.dto.PaySingleBillRequest;
import com.erp.modules.ap.domain.dto.PaymentRunRequest;
import com.erp.modules.ap.domain.entity.ApPayment;
import com.erp.modules.ap.domain.entity.ApPaymentAllocation;
import com.erp.modules.ap.domain.entity.PaymentRun;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.repository.ApPaymentAllocationRepository;
import com.erp.modules.ap.repository.ApPaymentRepository;
import com.erp.modules.ap.repository.PaymentRunRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.cashbank.domain.dto.CashAccountGlResolutionDto;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.cashbank.service.CashBankAccountResolver;
import com.erp.modules.cashbank.service.CashTransactionRecorder;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.purchases.domain.enums.PurchaseOrderOrigin;
import com.erp.modules.tax.service.WhtCaptureService;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.money.ConvertedAmount;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.common.money.CurrencyConversionService;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * K3 follow-up — cash must wait for the post-hoc ratification of a direct goods receipt.
 *
 * <p>A direct receipt (goods with no prior LPO) is exempt from PO pre-approval by design and raises
 * a RATIFICATION request instead. Until this gate existed the bill for such a receipt could be
 * entered, matched and PAID before any manager opened the request, which made the control advisory
 * on the only side that matters.
 *
 * <p>What is pinned here:
 * <ul>
 *   <li>a bill against an ordinary (MANUAL) PO is untouched;</li>
 *   <li>a bill against an unratified DIRECT_RECEIPT PO is refused at payment;</li>
 *   <li>the same bill pays once the receipt is ratified;</li>
 *   <li>a payment run refuses bills the caller NAMED, and skips (rather than fails on) bills it
 *       selected by criteria.</li>
 * </ul>
 */
class ApPaymentRatificationGateTest {

    private static final Long   COMPANY_ID  = 10L;
    private static final Long   BRANCH_ID   = 20L;
    private static final Long   SUPPLIER_ID = 30L;
    private static final String COMPANY_UID = "CO-1";
    private static final String BILL_UID    = "BILL-1";
    private static final String OTHER_BILL_UID = "BILL-2";
    private static final String PO_UID      = "PO-UID-1";
    private static final LocalDate TODAY    = LocalDate.of(2026, 8, 9);

    private SupplierBillRepository        bills;
    private ApPaymentRepository           payments;
    private ApPaymentAllocationRepository allocations;
    private PaymentRunRepository          paymentRuns;
    private CompanyRepository             companies;
    private SupplierRepository            suppliers;
    private ApBillNumberGenerator         numbers;
    private GLPostingService              glPosting;
    private GLConfigResolver              glConfig;
    private CashBankAccountResolver       cashBankAccountResolver;
    private CashTransactionRecorder       cashTxnRecorder;
    private WhtCaptureService             whtCapture;
    private CurrencyConversionService     fxConversion;
    private PurchaseMatchReader           purchaseMatchReader;
    private ScopeGuard                    scopeGuard;
    private AuditService                  audit;
    private ApPaymentServiceImpl          service;

    @BeforeEach
    void setUp() {
        bills                   = mock(SupplierBillRepository.class);
        payments                = mock(ApPaymentRepository.class);
        allocations             = mock(ApPaymentAllocationRepository.class);
        paymentRuns             = mock(PaymentRunRepository.class);
        companies               = mock(CompanyRepository.class);
        suppliers               = mock(SupplierRepository.class);
        numbers                 = mock(ApBillNumberGenerator.class);
        glPosting               = mock(GLPostingService.class);
        glConfig                = mock(GLConfigResolver.class);
        cashBankAccountResolver = mock(CashBankAccountResolver.class);
        cashTxnRecorder         = mock(CashTransactionRecorder.class);
        whtCapture              = mock(WhtCaptureService.class);
        fxConversion            = mock(CurrencyConversionService.class);
        purchaseMatchReader     = mock(PurchaseMatchReader.class);
        scopeGuard              = mock(ScopeGuard.class);
        audit                   = mock(AuditService.class);

        // cashAccounts only ever NAMES the account a payment posted to (UAT, 2026-08); this suite is
        // about whether cash may leave at all, so the default empty answer is the right stub.
        service = new ApPaymentServiceImpl(
                bills, payments, allocations, paymentRuns, companies, suppliers, numbers,
                glPosting, glConfig, cashBankAccountResolver, mock(CashBankAccountRepository.class),
                cashTxnRecorder, whtCapture,
                fxConversion, new DirectReceiptRatificationGuard(purchaseMatchReader),
                scopeGuard, audit);

        RequestContext.set(new RequestContext.Principal(
                1L, "ap@test.com", false, COMPANY_ID, BRANCH_ID, null));

        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        when(company.getBaseCurrency()).thenReturn("TZS");
        when(companies.findByUid(COMPANY_UID)).thenReturn(Optional.of(company));
        when(companies.findById(COMPANY_ID)).thenReturn(Optional.of(company));

        when(numbers.nextPayment(COMPANY_ID)).thenReturn("PAY-0001");
        when(numbers.nextPaymentRun(COMPANY_ID)).thenReturn("RUN-0001");
        when(payments.save(any(ApPayment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRuns.save(any(PaymentRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(allocations.save(any(ApPaymentAllocation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(allocations.findByApPaymentId(any())).thenReturn(List.of());

        when(fxConversion.toBase(any(), anyString(), anyLong(), any()))
                .thenReturn(new ConvertedAmount(BigDecimal.ONE, BigDecimal.ONE, Instant.now()));

        ChartOfAccount account = mock(ChartOfAccount.class);
        when(account.getId()).thenReturn(500L);
        when(glConfig.resolve(anyLong(), any())).thenReturn(account);
        when(cashBankAccountResolver.resolve(anyLong(), any()))
                .thenReturn(new CashAccountGlResolutionDto(700L, "CB-1", 600L, "1000"));
        when(glPosting.post(any())).thenReturn(journalEntry());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // paySingle
    // -------------------------------------------------------------------------

    @Test
    void paySingle_billAgainstManualPo_isUnaffected() {
        givenOpenBill(BILL_UID, PO_UID);
        givenBackingOrder(PurchaseOrderOrigin.MANUAL, "PENDING");

        assertThatCode(() -> service.paySingle(paySingle(BILL_UID))).doesNotThrowAnyException();
        verify(glPosting).post(any());
    }

    @Test
    void paySingle_billWithNoPurchaseOrder_isUnaffected() {
        givenOpenBill(BILL_UID, null);

        assertThatCode(() -> service.paySingle(paySingle(BILL_UID))).doesNotThrowAnyException();
        verify(glPosting).post(any());
    }

    @Test
    void paySingle_unratifiedDirectReceipt_isRefusedAndNoCashLeaves() {
        givenOpenBill(BILL_UID, PO_UID);
        givenBackingOrder(PurchaseOrderOrigin.DIRECT_RECEIPT, "PENDING");

        assertThatThrownBy(() -> service.paySingle(paySingle(BILL_UID)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cannot be paid yet")
                .hasMessageContaining("waiting for a manager to ratify");

        // Nothing posted, nothing saved — the refusal happens before any state is touched.
        verify(glPosting, never()).post(any());
        verify(payments, never()).save(any(ApPayment.class));
        verify(cashTxnRecorder, never()).recordSettlement(
                any(), any(), any(), any(), any(), any(), anyString(), anyString(), anyString(),
                any(), any());
    }

    @Test
    void paySingle_refusedRatification_isRefusedWithItsOwnGuidance() {
        givenOpenBill(BILL_UID, PO_UID);
        givenBackingOrder(PurchaseOrderOrigin.DIRECT_RECEIPT, "REJECTED");

        assertThatThrownBy(() -> service.paySingle(paySingle(BILL_UID)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("did not ratify");

        verify(glPosting, never()).post(any());
    }

    @Test
    void paySingle_sameBillPaysOnceTheReceiptIsRatified() {
        givenOpenBill(BILL_UID, PO_UID);
        givenBackingOrder(PurchaseOrderOrigin.DIRECT_RECEIPT, "APPROVED");

        assertThatCode(() -> service.paySingle(paySingle(BILL_UID))).doesNotThrowAnyException();
        verify(glPosting).post(any());
    }

    @Test
    void paySingle_refusalNamesNothingInternal() {
        givenOpenBill(BILL_UID, PO_UID);
        givenBackingOrder(PurchaseOrderOrigin.DIRECT_RECEIPT, "PENDING");

        assertThatThrownBy(() -> service.paySingle(paySingle(BILL_UID)))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(ex.getMessage())
                        .doesNotContain(PO_UID)
                        .doesNotContain(BILL_UID)
                        .doesNotContain("DIRECT_RECEIPT")
                        .doesNotContain("Exception"));
    }

    // -------------------------------------------------------------------------
    // paymentRun
    // -------------------------------------------------------------------------

    @Test
    void paymentRun_billsNamedByCaller_areRefusedNotSilentlyDropped() {
        SupplierBill gated = bill(41L, BILL_UID, PO_UID);
        when(bills.findOpenByUids(COMPANY_ID, List.of(BILL_UID))).thenReturn(List.of(gated));
        givenBackingOrder(PurchaseOrderOrigin.DIRECT_RECEIPT, "PENDING");

        assertThatThrownBy(() -> service.paymentRun(runNaming(List.of(BILL_UID))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("waiting for a manager to ratify");

        verify(glPosting, never()).post(any());
    }

    @Test
    void paymentRun_criteriaSelected_skipsTheGatedBillAndPaysTheRest() {
        SupplierBill gated = bill(41L, BILL_UID, PO_UID);
        SupplierBill payable = bill(42L, OTHER_BILL_UID, null);
        when(bills.findOpenForPaymentAllSuppliers(any(), any()))
                .thenReturn(List.of(gated, payable));
        givenBackingOrder(PurchaseOrderOrigin.DIRECT_RECEIPT, "PENDING");

        service.paymentRun(runByCriteria());

        // The run went ahead for the payable bill only: one allocation, and the held bill was
        // never settled. Failing the whole run would let one unratified delivery freeze every
        // supplier payment in the company.
        verify(allocations).save(any(ApPaymentAllocation.class));
        verify(bills).save(payable);
        verify(bills, never()).save(gated);
        verify(glPosting).post(any());
    }

    @Test
    void paymentRun_everySelectedBillGated_refusesWithTheRatificationReason() {
        SupplierBill gated = bill(41L, BILL_UID, PO_UID);
        when(bills.findOpenForPaymentAllSuppliers(any(), any())).thenReturn(List.of(gated));
        givenBackingOrder(PurchaseOrderOrigin.DIRECT_RECEIPT, "PENDING");

        assertThatThrownBy(() -> service.paymentRun(runByCriteria()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Nothing in this payment run can be paid yet")
                .hasMessageContaining("waiting for a manager to ratify");

        verify(glPosting, never()).post(any());
    }

    @Test
    void paymentRun_ratifiedDirectReceipt_isPaidLikeAnyOtherBill() {
        SupplierBill ratified = bill(41L, BILL_UID, PO_UID);
        when(bills.findOpenForPaymentAllSuppliers(any(), any())).thenReturn(List.of(ratified));
        givenBackingOrder(PurchaseOrderOrigin.DIRECT_RECEIPT, "APPROVED");

        service.paymentRun(runByCriteria());

        verify(bills).save(ratified);
        verify(glPosting).post(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void givenOpenBill(String billUid, String purchaseOrderUid) {
        // Build the mock BEFORE opening the when(...) — stubbing a mock inside an unfinished
        // thenReturn() is what Mockito calls UnfinishedStubbing.
        SupplierBill open = bill(41L, billUid, purchaseOrderUid);
        when(bills.findOpenByUids(COMPANY_ID, List.of(billUid))).thenReturn(List.of(open));
    }

    private void givenBackingOrder(PurchaseOrderOrigin origin, String approvalStatus) {
        when(purchaseMatchReader.findPo(PO_UID)).thenReturn(
                Optional.of(DirectReceiptRatificationGuardTest.po(origin, approvalStatus)));
    }

    private static SupplierBill bill(Long id, String uid, String purchaseOrderUid) {
        SupplierBill bill = mock(SupplierBill.class);
        CurrencyCode currency = mock(CurrencyCode.class);
        when(currency.value()).thenReturn("TZS");
        when(bill.getId()).thenReturn(id);
        when(bill.getUid()).thenReturn(uid);
        when(bill.getCompanyId()).thenReturn(COMPANY_ID);
        when(bill.getBranchId()).thenReturn(BRANCH_ID);
        when(bill.getSupplierId()).thenReturn(SUPPLIER_ID);
        when(bill.getCurrency()).thenReturn(currency);
        when(bill.getPurchaseOrderUid()).thenReturn(purchaseOrderUid);
        when(bill.getOutstandingAmount()).thenReturn(new BigDecimal("1000"));
        when(bill.getGrossAmount()).thenReturn(new BigDecimal("1000"));
        when(bill.getBaseOutstandingAmount()).thenReturn(new BigDecimal("1000"));
        when(bill.getFxRate()).thenReturn(BigDecimal.ONE);
        return bill;
    }

    private static PaySingleBillRequest paySingle(String billUid) {
        return new PaySingleBillRequest(
                COMPANY_UID, billUid, new BigDecimal("1000"), TODAY, "CASH", null,
                null, null, null, null, null);
    }

    private static PaymentRunRequest runNaming(List<String> billUids) {
        return new PaymentRunRequest(
                COMPANY_UID, null, TODAY, TODAY, "CASH", null, billUids, null, null, null);
    }

    private static PaymentRunRequest runByCriteria() {
        return new PaymentRunRequest(
                COMPANY_UID, null, TODAY, TODAY, "CASH", null, null, null, null, null);
    }

    private static JournalEntryDto journalEntry() {
        return new JournalEntryDto(
                1L, "JE-UID-1", COMPANY_ID, "B-1", TODAY, 1L, "AP Payment",
                JournalSourceType.AP_PAYMENT, "PAY-0001", null, null, false,
                "TZS", new BigDecimal("1000"), new BigDecimal("1000"), Instant.now(), List.of());
    }
}
