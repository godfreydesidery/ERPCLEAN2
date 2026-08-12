package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.ap.domain.dto.BillLineRequest;
import com.erp.modules.ap.domain.dto.EnterBillRequest;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.entity.SupplierBillLine;
import com.erp.modules.ap.repository.SupplierBillLineRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Supplier;
import com.erp.modules.parties.repository.PaymentTermsRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.purchases.domain.dto.PurchaseOrderLineDto;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Mock-based unit test for the ADR-0041 D4 PO→bill dimension copy in
 * {@link SupplierBillServiceImpl#enterBill}: when a bill is raised from a PO and a bill line maps
 * to a PO line, the PO line's cost-centre / department flow onto the matching bill line — but a
 * caller-supplied tag always wins (only an unset tag is inherited).
 */
class SupplierBillPoDimensionCopyTest {

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

    private static final Long COMPANY_ID = 10L;
    private static final String PO_UID    = "PO-AAA";
    private static final String PO_LINE_UID = "POL-1";

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
        service = new SupplierBillServiceImpl(bills, lines, suppliers, paymentTermsRepo,
                companies, chartOfAccounts, purchaseMatchReader,
                new DirectReceiptRatificationGuard(purchaseMatchReader),
                new BillComparisonReader(mock(com.erp.modules.ap.repository.BillMatchRepository.class)),
                scopeGuard, audit);

        RequestContext.set(new RequestContext.Principal(1L, "user", true, COMPANY_ID, 20L, null));

        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        when(company.getBaseCurrency()).thenReturn("TZS");
        when(companies.findByUid("CO")).thenReturn(Optional.of(company));
        when(companies.findById(COMPANY_ID)).thenReturn(Optional.of(company));

        Supplier supplier = mock(Supplier.class);
        when(supplier.getId()).thenReturn(30L);
        when(supplier.getPaymentTermsId()).thenReturn(null);
        when(supplier.getPaymentTermsDays()).thenReturn(null);
        when(suppliers.findByCompanyIdAndUid(COMPANY_ID, "SUP")).thenReturn(Optional.of(supplier));

        when(bills.existsByCompanyIdAndSupplierIdAndSupplierInvoiceNo(
                anyLong(), anyLong(), anyString())).thenReturn(false);
        // save returns the same bill (id stays null — fine for the line constructor's Long arg)
        when(bills.save(any(SupplierBill.class))).thenAnswer(inv -> inv.getArgument(0));
        when(lines.save(any(SupplierBillLine.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private EnterBillRequest billFromPo(BillLineRequest line) {
        return new EnterBillRequest(
                "CO", "SUP", "INV-1", PO_UID,
                java.time.LocalDate.now(), null, null,
                BigDecimal.ZERO, "TZS", null, List.of(line));
    }

    /** Build a PO-line DTO with the given dimensions (only the dimension fields are load-bearing). */
    private PurchaseOrderLineDto poLine(Long cc, Long dept) {
        return new PurchaseOrderLineDto(
                1L, PO_LINE_UID, 99L, (short) 1, 100L, "P", "Prod", 200L, "PCS",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, null, null, null,
                BigDecimal.ONE, false, BigDecimal.TEN, BigDecimal.TEN, "TZS",
                cc, dept);
    }

    @Test
    void enterBill_fromPo_inheritsPoLineDimensions_whenBillLineUntagged() {
        when(purchaseMatchReader.findPoLine(PO_UID, PO_LINE_UID))
                .thenReturn(Optional.of(poLine(111L, 222L)));

        BillLineRequest line = new BillLineRequest(
                100L, PO_LINE_UID, null, "Widget", new BigDecimal("2"), new BigDecimal("5"));

        service.enterBill(billFromPo(line));

        SupplierBillLine saved = captureSavedLine();
        assertThat(saved.getCostCentreValueId()).isEqualTo(111L);
        assertThat(saved.getDepartmentValueId()).isEqualTo(222L);
    }

    @Test
    void enterBill_fromPo_callerSuppliedTagsWin_overPoLine() {
        when(purchaseMatchReader.findPoLine(PO_UID, PO_LINE_UID))
                .thenReturn(Optional.of(poLine(111L, 222L)));

        // caller tags cost-centre explicitly (777); department left null → inherited (222)
        BillLineRequest line = new BillLineRequest(
                100L, PO_LINE_UID, null, "Widget", new BigDecimal("2"), new BigDecimal("5"),
                null, null, null, 777L, null);

        service.enterBill(billFromPo(line));

        SupplierBillLine saved = captureSavedLine();
        assertThat(saved.getCostCentreValueId()).isEqualTo(777L);   // caller wins
        assertThat(saved.getDepartmentValueId()).isEqualTo(222L);   // inherited
    }

    @Test
    void enterBill_noPo_noDimensionCopy() {
        BillLineRequest line = new BillLineRequest(
                100L, null, null, "Widget", new BigDecimal("2"), new BigDecimal("5"));

        EnterBillRequest req = new EnterBillRequest(
                "CO", "SUP", "INV-2", null,
                java.time.LocalDate.now(), null, null,
                BigDecimal.ZERO, "TZS", null, List.of(line));

        service.enterBill(req);

        SupplierBillLine saved = captureSavedLine();
        assertThat(saved.getCostCentreValueId()).isNull();
        assertThat(saved.getDepartmentValueId()).isNull();
    }

    private SupplierBillLine captureSavedLine() {
        org.mockito.ArgumentCaptor<SupplierBillLine> captor =
                org.mockito.ArgumentCaptor.forClass(SupplierBillLine.class);
        org.mockito.Mockito.verify(lines).save(captor.capture());
        return captor.getValue();
    }
}
