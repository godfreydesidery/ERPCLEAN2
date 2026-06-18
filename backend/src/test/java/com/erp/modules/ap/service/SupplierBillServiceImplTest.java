package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    // Helpers
    // -------------------------------------------------------------------------

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
