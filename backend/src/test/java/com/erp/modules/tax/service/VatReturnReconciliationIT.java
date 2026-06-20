package com.erp.modules.tax.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
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
import com.erp.modules.parties.domain.dto.CreateAgentRequest;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.AgentService;
import com.erp.modules.parties.service.CustomerService;
import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.PriceListService;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.AddPaymentRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.modules.sales.service.TaxRateSeeder;
import com.erp.modules.tax.domain.dto.FileVatReturnRequest;
import com.erp.modules.tax.domain.dto.OpenVatReturnRequest;
import com.erp.modules.tax.domain.dto.VatReturnDto;
import com.erp.modules.tax.domain.enums.VatReturnStatus;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Acceptance-bar integration tests for the VAT Return GL reconciliation (BR-VAT-08, ADR-0017 D-8).
 *
 * <p>The chief acceptance bar: a FILED VAT return's settlement journal must reconcile to the
 * period's GL control-account movements — output VAT on 2200, settlement legs balanced, correct
 * DR/CR on VAT_PAYABLE (2200) and VAT_DUE (2300).
 *
 * <p>Bars:
 * <ol>
 *   <li>File a return with output VAT from a finalised sales invoice: outputVat == 180 (18% of 1000).
 *   <li>Settlement journal exists, balances (Σdebit == Σcredit), and has the exact legs:
 *       DR VAT_PAYABLE (2200) == 180, CR VAT_DUE (2300) == 180; no VAT_INPUT leg (no input).
 *   <li>Return status is FILED, postedJournalUid is set, sourceType is VAT_RETURN.
 *   <li>recompute after dispatch picks up the output VAT: return.outputVat == 180.
 * </ol>
 */
class VatReturnReconciliationIT extends PostgresIntegrationTest {

    // ---- Sales prerequisites (copied from SalesPostingHandlerIT) ----
    @Autowired private SalesInvoiceService    salesInvoiceService;
    @Autowired private TaxRateSeeder          taxRateSeeder;
    @Autowired private CustomerService        customerService;
    @Autowired private AgentService           agentService;
    @Autowired private ProductService         productService;
    @Autowired private PriceListService       priceListService;
    @Autowired private UnitOfMeasureService   unitService;
    @Autowired private DomainEventRepository  domainEventRepository;
    @Autowired private DomainEventDispatcher  dispatcher;

    // ---- GL repositories ----
    @Autowired private JournalEntryRepository     journalEntryRepo;
    @Autowired private JournalLineRepository      journalLineRepo;
    @Autowired private ChartOfAccountRepository   accountRepo;

    // ---- GL seed services ----
    @Autowired private ChartOfAccountService  chartOfAccountService;
    @Autowired private FiscalCalendarService  fiscalCalendarService;
    @Autowired private GlConfigService        glConfigService;

    // ---- Tax services under test ----
    @Autowired private VatReturnService vatReturnService;

    // ---- IAM ----
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  companyUid;
    private String  customerUid;
    private String  agentUid;
    private String  pcsUid;
    private String  priceListUid;
    private String  productUid;

    // 1 unit × 1000 TZS + 18% VAT = 1180 gross, 180 output VAT
    private static final BigDecimal GROSS_1180 = new BigDecimal("1180");
    private static final BigDecimal VAT_180    = new BigDecimal("180");

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("VAT Recon IT Org"));
        company    = companies.save(new Company(org, "VATRC", "VAT Recon IT Co"));
        branch     = branches.save(new Branch(company, "VATRC1", "VAT Recon IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("vatrc_root", passwordEncoder.encode("VatRc0t1!"), "VAT Recon Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "vatrc_root", true, company.getId(), branch.getId(), null));

        // Sales prerequisites (matches SalesPostingHandlerIT setUp exactly)
        taxRateSeeder.seedDefaults(company.getId());

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(companyUid, "PCS", "Pieces")).uid();

        priceListUid = priceListService.create(
                new CreatePriceListRequest(companyUid, "RETAIL", "Retail")).uid();

        productUid = productService.create(new CreateProductRequest(
                companyUid, null, "VAT Recon Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD, null, null, null, null, null, null, null, null, null)).uid();
        productService.setPrice(productUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "TZS")));

        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "VAT Recon Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).uid();

        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "VAT Recon Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();

        // GL prerequisites — CoA (incl. 1400/1500/2300/2400), fiscal year, gl_configs
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1–2–3: file return with output VAT → settlement journal correct
    // =========================================================================

    /**
     * BR-VAT-08, ADR-0017 D-8.
     *
     * <p>Steps:
     * 1. Create + finalise a CASH_WALK_IN sales invoice: 1×1000+18% VAT = gross 1180, output VAT 180.
     * 2. Dispatch SALE.FINALISED → SalesPostingHandler credits VAT_PAYABLE (2200) by 180.
     * 3. Open + recompute a VAT return for the current month → outputVat == 180.
     * 4. File the return → status FILED; postedJournalUid set; settlement journal:
     *    - balanced (Σdebit == Σcredit)
     *    - DR VAT_PAYABLE (2200) == 180
     *    - CR VAT_DUE (2300) == 180
     *    - no VAT_INPUT (1400) leg (no input VAT in this period)
     *    - sourceType == VAT_RETURN, sourceRef == return uid.
     */
    @Test
    void file_withOutputVat_postsSettlementJournal_andReconciles() {
        // Step 1: finalise the invoice
        SalesInvoiceDto invoice = createAndFinaliseInvoice();

        // Step 2: dispatch SALE.FINALISED so SalesPostingHandler credits 2200 by 180
        DomainEvent finalisedEvent = getPendingFinalisedEvent(invoice.uid());
        dispatcher.dispatchOne(finalisedEvent.getId());

        // Restore context after dispatcher's REQUIRES_NEW boundary
        RequestContext.set(new RequestContext.Principal(
                rootId, "vatrc_root", true, company.getId(), branch.getId(), null));

        // Step 3: open a VAT return for the current month and recompute
        YearMonth now = YearMonth.now();
        VatReturnDto opened = vatReturnService.open(
                new OpenVatReturnRequest(companyUid, now.getYear(), now.getMonthValue()));

        // Recompute to pick up the finalised invoice's output VAT
        VatReturnDto recomputed = vatReturnService.recompute(opened.uid());

        assertThat(recomputed.outputVat())
                .as("outputVat must equal the invoice VAT after recompute (BR-VAT-08)")
                .isEqualByComparingTo(VAT_180);
        assertThat(recomputed.inputVat())
                .as("inputVat must be zero — no AP bills in this test")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // Step 4: file the return
        VatReturnDto filed = vatReturnService.file(recomputed.uid(),
                new FileVatReturnRequest("TRA-RECON-001",
                        LocalDate.of(now.getYear(), now.getMonthValue(), 20)));

        // Status + lock assertions
        assertThat(filed.status())
                .as("status must be FILED after file()")
                .isEqualTo(VatReturnStatus.FILED);
        assertThat(filed.filedAt()).isNotNull();
        assertThat(filed.postedJournalUid())
                .as("postedJournalUid must be set when output VAT > 0")
                .isNotBlank();

        // --- Settlement journal assertions ---
        var glEntry = journalEntryRepo.findByUid(filed.postedJournalUid())
                .orElseThrow(() -> new AssertionError(
                        "Settlement GL entry not found: " + filed.postedJournalUid()));

        // sourceType == VAT_RETURN, sourceRef == return uid
        assertThat(glEntry.getSourceType().name())
                .as("settlement entry sourceType must be VAT_RETURN (ADR-0017 D-8)")
                .isEqualTo("VAT_RETURN");
        assertThat(glEntry.getSourceRef())
                .as("settlement entry sourceRef must equal the return uid")
                .isEqualTo(filed.uid());

        List<?> lines = journalLineRepo.findByEntryIdOrderByLineNo(glEntry.getId());
        assertThat(lines)
                .as("settlement journal must have at least 2 legs (DR + CR)")
                .hasSizeGreaterThanOrEqualTo(2);

        // Balanced
        BigDecimal sumDebit  = sumDebit(glEntry.getId());
        BigDecimal sumCredit = sumCredit(glEntry.getId());
        assertThat(sumDebit)
                .as("settlement journal must be balanced: Σdebit == Σcredit")
                .isEqualByComparingTo(sumCredit);

        // Debit side == output VAT == 180
        assertThat(sumDebit)
                .as("total debit must equal output VAT 180 (no input, so leg = O alone)")
                .isEqualByComparingTo(VAT_180);

        // DR VAT_PAYABLE (2200) == 180
        Long vatPayableId = accountId("2200");
        BigDecimal drVatPayable = debitOnAccount(glEntry.getId(), vatPayableId);
        assertThat(drVatPayable)
                .as("DR VAT_PAYABLE (2200) must equal output VAT 180 (ADR-0017 D-8 leg 1)")
                .isEqualByComparingTo(VAT_180);

        // CR VAT_DUE (2300) == 180  (net payable to TRA)
        Long vatDueId = accountId("2300");
        BigDecimal crVatDue = creditOnAccount(glEntry.getId(), vatDueId);
        assertThat(crVatDue)
                .as("CR VAT_DUE (2300) must equal net payable 180 (ADR-0017 D-8 leg 3)")
                .isEqualByComparingTo(VAT_180);

        // No VAT_INPUT (1400) leg — no input VAT in this period
        Long vatInputId = accountId("1400");
        BigDecimal crVatInput = creditOnAccount(glEntry.getId(), vatInputId);
        assertThat(crVatInput)
                .as("no CR VAT_INPUT (1400) leg expected when input VAT == 0")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // Bar 4: outputVat in return matches invoice VAT precisely (recompute only)
    // =========================================================================

    @Test
    void recompute_afterSalesFinalised_outputVatMatchesInvoiceVat() {
        SalesInvoiceDto invoice = createAndFinaliseInvoice();
        dispatcher.dispatchOne(getPendingFinalisedEvent(invoice.uid()).getId());

        RequestContext.set(new RequestContext.Principal(
                rootId, "vatrc_root", true, company.getId(), branch.getId(), null));

        YearMonth now = YearMonth.now();
        VatReturnDto opened = vatReturnService.open(
                new OpenVatReturnRequest(companyUid, now.getYear(), now.getMonthValue()));
        VatReturnDto recomputed = vatReturnService.recompute(opened.uid());

        assertThat(recomputed.outputVat())
                .as("return.outputVat must equal invoice.vatTotalAmount (BR-VAT-08)")
                .isEqualByComparingTo(VAT_180);
        assertThat(recomputed.netVat())
                .as("net = output(180) − input(0) + adj(0) − openingCredit(0) = 180")
                .isEqualByComparingTo(VAT_180);
        assertThat(recomputed.closingCredit())
                .as("net > 0 → closing credit = 0")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Create + finalise a CASH_WALK_IN invoice: 1×1000 + 18% = 1180 gross, 180 VAT. */
    private SalesInvoiceDto createAndFinaliseInvoice() {
        SalesInvoiceDto draft = salesInvoiceService.create(
                new CreateSalesInvoiceRequest(
                        companyUid, customerUid, agentUid, "TZS", null, null));
        salesInvoiceService.addLine(draft.uid(), new AddInvoiceLineRequest(
                productUid, pcsUid, new BigDecimal("1"), null, null));
        salesInvoiceService.addPayment(draft.uid(), new AddPaymentRequest(
                TenderType.CASH, GROSS_1180, "TZS", null));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        return salesInvoiceService.getByUid(draft.uid());
    }

    private DomainEvent getPendingFinalisedEvent(String invoiceUid) {
        return domainEventRepository.findAll().stream()
                .filter(e -> DomainEventType.SALE_FINALISED.equals(e.getEventType())
                          && invoiceUid.equals(e.getAggregateUid()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No SALE.FINALISED event for invoice uid=" + invoiceUid));
    }

    private Long accountId(String code) {
        return accountRepo.findByCompanyIdAndAccountCode(company.getId(), code)
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("Account not found: " + code));
    }

    private BigDecimal sumDebit(Long entryId) {
        return journalLineRepo.findByEntryIdOrderByLineNo(entryId).stream()
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumCredit(Long entryId) {
        return journalLineRepo.findByEntryIdOrderByLineNo(entryId).stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal debitOnAccount(Long entryId, Long accountId) {
        return journalLineRepo.findByEntryIdOrderByLineNo(entryId).stream()
                .filter(l -> accountId.equals(l.getAccountId()))
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal creditOnAccount(Long entryId, Long accountId) {
        return journalLineRepo.findByEntryIdOrderByLineNo(entryId).stream()
                .filter(l -> accountId.equals(l.getAccountId()))
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
