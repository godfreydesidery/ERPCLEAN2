package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ap.domain.dto.BillLineRequest;
import com.erp.modules.ap.domain.dto.EnterBillRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.BillMatchRepository;
import com.erp.modules.ap.repository.SupplierBillLineRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
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
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.service.SupplierService;
import com.erp.platform.common.api.ConflictException;
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
 * AP-01 + AP-05 against a real database.
 *
 * <ul>
 *   <li>A held bill can be deleted — lines and match rows with it — and the same supplier invoice
 *       number can then be entered again (the duplicate guard no longer sees it).
 *   <li>A posted (MATCHED) bill cannot be deleted.
 *   <li>The "already billed on other bills" sum counts held bills, ignores drafts and the bill
 *       itself, and forgets a deleted bill.
 * </ul>
 */
class ApBillCorrectionIT extends PostgresIntegrationTest {

    @Autowired private SupplierBillService           billService;
    @Autowired private SupplierBillCorrectionService corrections;
    @Autowired private BillMatchService              matchService;
    @Autowired private ApGlSeeder                    apGlSeeder;
    @Autowired private CashBankSeeder                cashBankSeeder;
    @Autowired private SupplierService               supplierService;
    @Autowired private SupplierBillRepository        billRepo;
    @Autowired private SupplierBillLineRepository    lineRepo;
    @Autowired private BillMatchRepository           matchRepo;
    @Autowired private ChartOfAccountService         chartOfAccountService;
    @Autowired private FiscalCalendarService         fiscalCalendarService;
    @Autowired private GlConfigService               glConfigService;
    @Autowired private OrganisationRepository        organisations;
    @Autowired private CompanyRepository             companies;
    @Autowired private BranchRepository              branches;
    @Autowired private AppUserRepository             users;
    @Autowired private PasswordEncoder               passwordEncoder;
    @Autowired private IamTestData                   testData;

    private Company company;
    private String  companyUid;
    private String  supplierUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("BillFix IT Org"));
        company    = companies.save(new Company(org, "BFIT", "BillFix IT Co"));
        Branch branch = branches.save(new Branch(company, "BFIT1", "BillFix IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("bf_root", passwordEncoder.encode("BfR00t!Xx"), "BillFix Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root = users.save(root);

        RequestContext.set(new RequestContext.Principal(
                root.getId(), "bf_root", true, company.getId(), branch.getId(), null));

        supplierUid = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "BF Supplier Ltd",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null)).uid();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        apGlSeeder.seedDefaults(company.getId());
        cashBankSeeder.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void heldBill_canBeDeleted_andTheInvoiceEnteredAgain() {
        // A receipt line that cannot be resolved → the match fails closed → HELD with a number.
        SupplierBillDto held = enter("INV-BF-001", "GRL-UNKNOWN-0000000000001", "5");
        matchService.runMatch(held.uid());
        var heldRow = billRepo.findByUid(held.uid()).orElseThrow();
        assertThat(heldRow.getStatus()).isEqualTo(SupplierBillStatus.HELD);
        assertThat(matchRepo.findBySupplierBillId(heldRow.getId())).isNotEmpty();

        // The duplicate guard blocks re-entry while the held bill exists …
        assertThatThrownBy(() -> enter("INV-BF-001", null, "5"))
                .hasMessageContaining("already been entered");

        corrections.deleteUnposted(held.uid());

        assertThat(billRepo.findByUid(held.uid())).isEmpty();
        assertThat(lineRepo.findBySupplierBillIdOrderByLineNo(heldRow.getId())).isEmpty();
        assertThat(matchRepo.findBySupplierBillId(heldRow.getId())).isEmpty();

        // … and lets it through once the held bill is gone.
        SupplierBillDto again = enter("INV-BF-001", null, "5");
        assertThat(again.status()).isEqualTo(SupplierBillStatus.DRAFT);
    }

    @Test
    void matchedBill_cannotBeDeleted() {
        SupplierBillDto bill = enter("INV-BF-002", null, "1");
        matchService.runMatch(bill.uid());
        assertThat(billRepo.findByUid(bill.uid()).orElseThrow().getStatus())
                .isEqualTo(SupplierBillStatus.MATCHED);

        assertThatThrownBy(() -> corrections.deleteUnposted(bill.uid()))
                .isInstanceOf(ConflictException.class);
        assertThat(billRepo.findByUid(bill.uid())).isPresent();
    }

    @Test
    void billedElsewhereSum_countsNonDraftOtherBills_only() {
        String grl = "GRL-SHARED-000000000000001";
        SupplierBillDto first = enter("INV-BF-010", grl, "4");
        matchService.runMatch(first.uid());          // HELD — claims its 4
        SupplierBillDto draft = enter("INV-BF-011", grl, "7"); // DRAFT — claims nothing
        SupplierBillDto third = enter("INV-BF-012", grl, "3");
        matchService.runMatch(third.uid());          // HELD — claims its 3

        Long companyId = company.getId();
        Long draftId = billRepo.findByUid(draft.uid()).orElseThrow().getId();
        Long thirdId = billRepo.findByUid(third.uid()).orElseThrow().getId();

        assertThat(lineRepo.sumBilledQtyOnOtherBills(companyId, grl, draftId))
                .isEqualByComparingTo("7");
        assertThat(lineRepo.sumBilledQtyOnOtherBills(companyId, grl, thirdId))
                .as("the bill itself is excluded, the draft is not counted")
                .isEqualByComparingTo("4");

        corrections.deleteUnposted(first.uid());
        assertThat(lineRepo.sumBilledQtyOnOtherBills(companyId, grl, thirdId))
                .as("a deleted bill no longer claims the receipt")
                .isEqualByComparingTo("0");
    }

    private SupplierBillDto enter(String invoiceNo, String grLineUid, String qty) {
        return billService.enterBill(new EnterBillRequest(
                companyUid, supplierUid, invoiceNo, null,
                LocalDate.now(), LocalDate.now().plusDays(30),
                null, "TZS", null,
                List.of(new BillLineRequest(null, null, grLineUid, "Crates",
                        new BigDecimal(qty), new BigDecimal("1000")))));
    }
}
