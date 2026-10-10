package com.erp.modules.ar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import com.erp.modules.ar.domain.dto.ArReceiptDto;
import com.erp.modules.ar.domain.dto.RaiseCreditNoteRequest;
import com.erp.modules.ar.domain.dto.RecordReceiptRequest;
import com.erp.modules.ar.domain.dto.SetOpeningBalanceRequest;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
import com.erp.modules.cashbank.service.CashBankSeeder;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.CustomerService;
import com.erp.modules.sales.service.TaxRateSeeder;
import com.erp.platform.common.api.NotFoundException;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Receivables screens against a real database (2026-10-10 review): the customer and status
 * filters, the per-customer open-item grid, and the customer name on every row.
 */
class ArReceivablesWorkflowIT extends PostgresIntegrationTest {

    @Autowired private ArReceiptService receiptService;
    @Autowired private ArInvoiceService invoiceService;
    @Autowired private ArOpeningBalanceService openingBalanceService;
    @Autowired private ArCreditNoteService creditNoteService;
    @Autowired private ArGlSeeder arGlSeeder;
    @Autowired private TaxRateSeeder taxRateSeeder;
    @Autowired private CustomerService customerService;
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private GlConfigService glConfigService;
    @Autowired private CashBankSeeder cashBankSeeder;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private static final String TZS = "TZS";

    private Company company;
    private Branch branch;
    private String companyUid;
    private String kiboUid;
    private String mamboUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("AR Workflow IT Org"));
        company    = companies.save(new Company(org, "ARWIT", "AR Workflow IT Co"));
        branch     = branches.save(new Branch(company, "ARWIT1", "AR Workflow IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("arw_root", passwordEncoder.encode("RootPass1!"), "ARW Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root = users.save(root);

        RequestContext.set(new RequestContext.Principal(
                root.getId(), "arw_root", true, company.getId(), branch.getId(), null));

        taxRateSeeder.seedDefaults(company.getId());
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        arGlSeeder.seedDefaults(company.getId());
        cashBankSeeder.seedDefaults(company.getId());

        kiboUid  = creditCustomer("Kibo Bar");
        mamboUid = creditCustomer("Mambo Lounge");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ── ARC-02: filters + open-item grid ──────────────────────────────────────

    @Test
    void invoiceList_customerUidFilter_returnsOnlyThatCustomersItems() {
        ArInvoiceDto kibo  = opening(kiboUid, "1000", LocalDate.now().minusDays(3));
        opening(mamboUid, "2000", LocalDate.now().minusDays(2));

        List<ArInvoiceDto> rows = invoiceService.list(company.getId(), null, kiboUid, null,
                PageRequest.of(0, 20)).getContent();

        assertThat(rows).extracting(ArInvoiceDto::uid).containsExactly(kibo.uid());
    }

    @Test
    void invoiceList_statusFilter_returnsOnlyMatchingStatuses() {
        ArInvoiceDto paid = opening(kiboUid, "1000", LocalDate.now().minusDays(5));
        ArInvoiceDto open = opening(kiboUid, "3000", LocalDate.now().minusDays(1));
        receiptService.recordAndAllocate(new RecordReceiptRequest(companyUid, kiboUid,
                new BigDecimal("1000"), TZS, LocalDate.now(), "CASH", null,
                List.of(new RecordReceiptRequest.AllocationLineRequest(paid.uid(),
                        new BigDecimal("1000")))));

        assertThat(invoiceService.list(company.getId(), null, null, "OPEN",
                PageRequest.of(0, 20)).getContent())
                .extracting(ArInvoiceDto::uid).containsExactly(open.uid());
        assertThat(invoiceService.list(company.getId(), null, kiboUid, "paid",
                PageRequest.of(0, 20)).getContent())
                .extracting(ArInvoiceDto::uid).containsExactly(paid.uid());
        assertThat(invoiceService.list(company.getId(), null, null, "OPEN,PARTIAL,PAID",
                PageRequest.of(0, 20)).getContent()).hasSize(2);
    }

    @Test
    void invoiceList_unknownStatus_isAFriendlyBadRequest() {
        assertThatThrownBy(() -> invoiceService.list(company.getId(), null, null, "UNPAID",
                PageRequest.of(0, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Choose Open, Partial, Paid or Written off");
    }

    @Test
    void invoiceList_customerUidFromAnotherCompany_isNotFound() {
        assertThatThrownBy(() -> invoiceService.list(company.getId(), null, "01UNKNOWNCUSTOMERUID00000",
                null, PageRequest.of(0, 20)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void invoiceRows_carryTheCustomerName() {
        opening(mamboUid, "2000", LocalDate.now());

        ArInvoiceDto row = invoiceService.list(company.getId(), null, null, null,
                PageRequest.of(0, 20)).getContent().get(0);

        assertThat(row.customerUid()).isEqualTo(mamboUid);
        assertThat(row.customerName()).isEqualTo("Mambo Lounge");
        assertThat(row.customerCode()).isNotBlank();
    }

    @Test
    void openItems_onlyThatCustomersOpenItems_oldestDueFirst() {
        ArInvoiceDto newer = opening(kiboUid, "500", LocalDate.now().minusDays(1));
        ArInvoiceDto older = opening(kiboUid, "700", LocalDate.now().minusDays(20));
        ArInvoiceDto paid  = opening(kiboUid, "100", LocalDate.now().minusDays(30));
        opening(mamboUid, "900", LocalDate.now().minusDays(40));
        receiptService.recordAndAllocate(new RecordReceiptRequest(companyUid, kiboUid,
                new BigDecimal("100"), TZS, LocalDate.now(), "CASH", null,
                List.of(new RecordReceiptRequest.AllocationLineRequest(paid.uid(),
                        new BigDecimal("100")))));

        List<ArInvoiceDto> open = invoiceService.listOpenForCustomer(company.getId(), null, kiboUid);

        assertThat(open).extracting(ArInvoiceDto::uid).containsExactly(older.uid(), newer.uid());
        assertThat(open).allSatisfy(i -> assertThat(i.status()).isIn(ArInvoiceStatus.OPEN,
                ArInvoiceStatus.PARTIAL));
    }

    @Test
    void receiptList_customerUidFilter_returnsOnlyThatCustomersReceipts() {
        ArReceiptDto kibo = onAccount(kiboUid, "1000");
        onAccount(mamboUid, "2000");

        List<ArReceiptDto> rows = receiptService.list(company.getId(), null, kiboUid,
                PageRequest.of(0, 20)).getContent();

        assertThat(rows).extracting(ArReceiptDto::uid).containsExactly(kibo.uid());
        assertThat(rows.get(0).customerName()).isEqualTo("Kibo Bar");
    }

    // ── ARC-03: credit note from the invoice row ──────────────────────────────

    @Test
    void creditNote_againstAnInvoice_takesTheCustomerFromTheInvoice() {
        ArInvoiceDto inv = opening(kiboUid, "1000", LocalDate.now().minusDays(2));

        creditNoteService.raise(new RaiseCreditNoteRequest(companyUid, "", inv.uid(),
                LocalDate.now(), new BigDecimal("200"), BigDecimal.ZERO, TZS, "2 crates returned"));

        assertThat(invoiceService.getByUid(inv.uid()).outstandingAmount())
                .isEqualByComparingTo("800");
    }

    @Test
    void creditNote_namingAnotherCustomer_isStillRefused() {
        ArInvoiceDto inv = opening(kiboUid, "1000", LocalDate.now().minusDays(2));
        RaiseCreditNoteRequest wrongCustomer = new RaiseCreditNoteRequest(companyUid, mamboUid,
                inv.uid(), LocalDate.now(), new BigDecimal("200"), BigDecimal.ZERO, TZS, "wrong");

        assertThatThrownBy(() -> creditNoteService.raise(wrongCustomer))
                .isInstanceOf(com.erp.platform.common.api.ConflictException.class);
    }

    // ── ARC-18 / LSF-18: the M-Pesa code is kept ──────────────────────────────

    @Test
    void receipt_keepsTheMobileMoneyReference() {
        ArReceiptDto saved = receiptService.recordAndAllocate(new RecordReceiptRequest(companyUid,
                kiboUid, new BigDecimal("5000"), TZS, LocalDate.now(), "MOBILE_MONEY",
                " QJK7XY12AB ", List.of()));

        assertThat(saved.bankReference()).isEqualTo("QJK7XY12AB");
        assertThat(receiptService.getByUid(saved.uid()).bankReference()).isEqualTo("QJK7XY12AB");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String creditCustomer(String name) {
        return customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, name,
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).uid();
    }

    private ArInvoiceDto opening(String customerUid, String amount, LocalDate date) {
        return openingBalanceService.setOpeningBalance(new SetOpeningBalanceRequest(
                companyUid, customerUid, new BigDecimal(amount), TZS, date, date.plusDays(30), null));
    }

    private ArReceiptDto onAccount(String customerUid, String amount) {
        return receiptService.recordAndAllocate(new RecordReceiptRequest(companyUid, customerUid,
                new BigDecimal(amount), TZS, LocalDate.now(), "CASH", null, List.of()));
    }
}
