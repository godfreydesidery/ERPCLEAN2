package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ap.domain.dto.BillLineRequest;
import com.erp.modules.ap.domain.dto.EnterBillRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.service.SupplierService;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link SupplierBillServiceImpl} (ADR-0015 D-1/D-2a/D-2b, FR-AP-01/02).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>enterBill persists a DRAFT bill with lines; grossAmount = net + VAT.
 *   <li>Duplicate supplier invoice number is rejected (duplicate-payable guard).
 *   <li>listByCompany/listBySupplier scope-checks; returns the entered bill.
 * </ol>
 */
class ApBillServiceIT extends PostgresIntegrationTest {

    @Autowired private SupplierBillService billService;
    @Autowired private SupplierService supplierService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  companyUid;
    private String  supplierUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("AP Bill IT Org"));
        company    = companies.save(new Company(org, "APBIL", "AP Bill IT Co"));
        branch     = branches.save(new Branch(company, "APBIL1", "AP Bill IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("ap_bill_root", passwordEncoder.encode("ApB1llR00t!"), "AP Bill Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "ap_bill_root", true, company.getId(), branch.getId(), null));

        supplierUid = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "IT Supplier Ltd",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS)).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: enterBill creates a DRAFT bill with lines
    // =========================================================================

    @Test
    void enterBill_persistsDraftBillWithLines() {
        SupplierBillDto dto = billService.enterBill(buildRequest("INV-001",
                new BigDecimal("1000.00"), new BigDecimal("1"), new BigDecimal("180.00")));

        assertThat(dto.uid()).isNotBlank();
        assertThat(dto.status()).isEqualTo(SupplierBillStatus.DRAFT);
        assertThat(dto.netAmount()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(dto.vatAmount()).isEqualByComparingTo(new BigDecimal("180.00"));
        assertThat(dto.grossAmount()).isEqualByComparingTo(new BigDecimal("1180.00"));
        assertThat(dto.outstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.lines()).hasSize(1);
        assertThat(dto.lines().get(0).unitCostAmount()).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    void enterBill_twoLines_grossIsSumOfLines() {
        SupplierBillDto dto = billService.enterBill(new EnterBillRequest(
                companyUid, supplierUid, "INV-002",
                null, // no PO
                LocalDate.now(), LocalDate.now().plusDays(30),
                new BigDecimal("50.00"), "TZS", null,
                List.of(
                        new BillLineRequest(null, null, null, "Item A",
                                new BigDecimal("2"), new BigDecimal("100.00")),
                        new BillLineRequest(null, null, null, "Item B",
                                new BigDecimal("3"), new BigDecimal("50.00")))));

        // net = 2*100 + 3*50 = 350; gross = 350 + 50 = 400
        assertThat(dto.netAmount()).isEqualByComparingTo(new BigDecimal("350.00"));
        assertThat(dto.grossAmount()).isEqualByComparingTo(new BigDecimal("400.00"));
        assertThat(dto.lines()).hasSize(2);
    }

    // =========================================================================
    // Bar 2: Duplicate invoice guard
    // =========================================================================

    @Test
    void enterBill_duplicateInvoiceNo_rejected() {
        billService.enterBill(buildRequest("INV-DUP",
                new BigDecimal("500.00"), new BigDecimal("1"), null));

        EnterBillRequest dup = buildRequest("INV-DUP",
                new BigDecimal("500.00"), new BigDecimal("1"), null);
        assertThatThrownBy(() -> billService.enterBill(dup))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INV-DUP");
    }

    // =========================================================================
    // Bar 3: listByCompany includes entered bill; listBySupplier same
    // =========================================================================

    @Test
    void listByCompany_includesEnteredBill() {
        billService.enterBill(buildRequest("INV-LIST",
                new BigDecimal("200.00"), new BigDecimal("1"), null));

        var page = billService.listByCompany(company.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(1);
        assertThat(page.getContent()).anyMatch(b -> "INV-LIST".equals(b.supplierInvoiceNo()));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private EnterBillRequest buildRequest(String invoiceNo, BigDecimal unitCost,
                                          BigDecimal qty, BigDecimal vatAmount) {
        return new EnterBillRequest(
                companyUid, supplierUid, invoiceNo,
                null,                          // no PO
                LocalDate.now(), LocalDate.now().plusDays(30),
                vatAmount, "TZS", null,
                List.of(new BillLineRequest(null, null, null, "Widget",
                        qty, unitCost)));
    }
}
