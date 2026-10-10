package com.erp.modules.gl.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ar.domain.entity.ArCreditNote;
import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.domain.enums.ArCreditNoteOrigin;
import com.erp.modules.ar.repository.ArCreditNoteRepository;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.cashbank.domain.dto.CreateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.service.CashBankAccountService;
import com.erp.modules.gl.domain.dto.CreateAccountRequest;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
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
import com.erp.modules.sales.domain.dto.UpdateSalesSettingsRequest;
import com.erp.modules.sales.domain.dto.VoidInvoiceRequest;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.modules.sales.service.SalesSettingsService;
import com.erp.modules.sales.service.TaxRateSeeder;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Trial-balance effects of the sale posting's tender split and the void clearing AR
 * (SAL-06 / ACC-04, ACC-05 / LSF-06 / POS-09, SAL-03).
 *
 * <p>Every scenario: a credit customer buys one item at 1,000 + 18% VAT = 1,180, through the real
 * services and the real outbox dispatch. The bar is the ledger, not the handler: AR control
 * (GL 1200) must equal the AR subledger (Σ open-item outstanding), and each tender must sit on
 * the GL account of the cash/bank account it was taken into.
 */
class SaleTenderPostingTrialBalanceIT extends PostgresIntegrationTest {

    private static final BigDecimal GROSS = new BigDecimal("1180");
    private static final BigDecimal DEPOSIT = new BigDecimal("400");
    private static final BigDecimal OUTSTANDING = new BigDecimal("780");

    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private TaxRateSeeder taxRateSeeder;
    @Autowired private SalesSettingsService salesSettingsService;
    @Autowired private CustomerService customerService;
    @Autowired private AgentService agentService;
    @Autowired private ProductService productService;
    @Autowired private PriceListService priceListService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private DomainEventDispatcher dispatcher;
    @Autowired private ArInvoiceRepository arInvoices;
    @Autowired private ArCreditNoteRepository arCreditNotes;
    @Autowired private JournalEntryRepository journalEntries;
    @Autowired private JournalLineRepository journalLines;
    @Autowired private ChartOfAccountRepository glAccounts;
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private GlConfigService glConfigService;
    @Autowired private CashBankAccountService cashBankAccountService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private String creditCustomerUid;
    private String agentUid;
    private String pcsUid;
    private String productUid;
    private Long cashGl;
    private Long arGl;
    private Long mpesaGl;
    private Long mpesaAccountId;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Tender TB IT Org"));
        company = companies.save(new Company(org, "TTBIT", "Tender TB IT Co"));
        Branch branch = branches.save(new Branch(company, "TTBIT1", "Tender TB IT Branch"));

        AppUser root = new AppUser("ttb_root", passwordEncoder.encode("RootPass1!"), "TTB Root");
        root.setRoot(true);
        root = users.save(inOrganisation(root, org.getId()));

        RequestContext.set(new RequestContext.Principal(
                root.getId(), "ttb_root", true, company.getId(), branch.getId(), null));

        taxRateSeeder.seedDefaults(company.getId());
        salesSettingsService.update(new UpdateSalesSettingsRequest(
                company.getUid(), false, null, "TZS", true));

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        String priceListUid = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();
        productUid = productService.create(new CreateProductRequest(
                company.getUid(), null, "TB Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null)).uid();
        productService.setPrice(productUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "TZS")));

        creditCustomerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Credit Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).uid();
        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "TB Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        cashGl = glAccountId("1000");
        arGl = glAccountId("1200");

        // An M-Pesa wallet: its own GL asset account and a cash/bank account linked to it.
        String mpesaGlUid = chartOfAccountService.create(new CreateAccountRequest(
                company.getUid(), "1099", "M-Pesa Wallet", AccountType.ASSET)).uid();
        mpesaGl = glAccountId("1099");
        mpesaAccountId = cashBankAccountService.create(new CreateCashBankAccountRequest(
                company.getUid(), null, "M-Pesa Till", CashBankAccountType.BANK,
                "Vodacom M-Pesa", "555123", null, mpesaGlUid, false)).id();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ── SAL-06 / ACC-04 ─────────────────────────────────────────────────────────

    @Test
    void creditSaleWithCashDeposit_depositReachesCash_arControlEqualsSubledger() {
        SalesInvoiceDto inv = creditSale(new AddPaymentRequest(
                TenderType.CASH, DEPOSIT, "TZS", null));

        assertThat(balance(cashGl)).isEqualByComparingTo(DEPOSIT);
        assertThat(balance(arGl)).isEqualByComparingTo(OUTSTANDING);
        assertThat(openItem(inv.uid()).getOutstandingAmount()).isEqualByComparingTo(OUTSTANDING);
        assertThat(balance(arGl)).isEqualByComparingTo(arSubledger());
        assertTrialBalanceBalanced();
    }

    // ── ACC-05 / LSF-06 / POS-09 ───────────────────────────────────────────────

    @Test
    void creditSaleWithMpesaDeposit_landsOnTheMpesaAccountsGl_notCash() {
        creditSale(new AddPaymentRequest(TenderType.MOBILE_MONEY, DEPOSIT, "TZS", "MP-1",
                mpesaAccountId, null, "QWE123", null));

        assertThat(balance(mpesaGl)).isEqualByComparingTo(DEPOSIT);
        assertThat(balance(cashGl)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balance(arGl)).isEqualByComparingTo(arSubledger());
        assertTrialBalanceBalanced();
    }

    // ── SAL-03 ──────────────────────────────────────────────────────────────────

    @Test
    void voidingAnOnAccountSale_clearsTheOpenItem_withNoSecondGlPost() {
        SalesInvoiceDto inv = creditSale();
        assertThat(balance(arGl)).isEqualByComparingTo(GROSS);

        voidAndDispatch(inv.uid());

        ArInvoice item = openItem(inv.uid());
        assertThat(item.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        List<ArCreditNote> notes = arCreditNotes.findByArInvoiceId(item.getId());
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).getOrigin()).isEqualTo(ArCreditNoteOrigin.SALE_VOID);
        assertThat(notes.get(0).getAmount()).isEqualByComparingTo(GROSS);
        assertThat(notes.get(0).getUnappliedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(notes.get(0).getGlEntryUid()).isNull();

        assertThat(balance(arGl)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(arSubledger()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(journalEntries.findAll().stream()
                .filter(e -> company.getId().equals(e.getCompanyId()))
                .filter(e -> e.getSourceType() == JournalSourceType.AR_CREDIT_NOTE))
                .as("the void's GL reversal already relieved AR control — no credit-note posting")
                .isEmpty();
        assertTrialBalanceBalanced();
    }

    @Test
    void voidingADepositSale_reversesCashAndAr_andClearsTheOpenItem_idempotently() {
        SalesInvoiceDto inv = creditSale(new AddPaymentRequest(
                TenderType.CASH, DEPOSIT, "TZS", null));

        DomainEvent voided = voidAndDispatch(inv.uid());
        dispatcher.dispatchOne(voided.getId()); // a redelivery must not raise a second note

        assertThat(balance(cashGl)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balance(arGl)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(arSubledger()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(arCreditNotes.findByArInvoiceId(openItem(inv.uid()).getId())).hasSize(1);
        assertTrialBalanceBalanced();
    }

    // -------------------------------------------------------------------------

    private SalesInvoiceDto creditSale(AddPaymentRequest... payments) {
        SalesInvoiceDto draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                company.getUid(), creditCustomerUid, agentUid, "TZS", null, null));
        salesInvoiceService.addLine(draft.uid(),
                new AddInvoiceLineRequest(productUid, pcsUid, BigDecimal.ONE, null, null));
        for (AddPaymentRequest p : payments) {
            salesInvoiceService.addPayment(draft.uid(), p);
        }
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(event(DomainEventType.SALE_FINALISED, draft.uid()).getId());
        SalesInvoiceDto inv = salesInvoiceService.getByUid(draft.uid());
        assertThat(inv.grossTotalAmount()).isEqualByComparingTo(GROSS);
        return inv;
    }

    private DomainEvent voidAndDispatch(String invoiceUid) {
        salesInvoiceService.voidInvoice(invoiceUid, new VoidInvoiceRequest("Wrong customer", null));
        DomainEvent voided = event(DomainEventType.SALE_VOIDED, invoiceUid);
        dispatcher.dispatchOne(voided.getId());
        return voided;
    }

    private DomainEvent event(String type, String invoiceUid) {
        return domainEventRepository.findAll().stream()
                .filter(e -> type.equals(e.getEventType()) && invoiceUid.equals(e.getAggregateUid()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + type + " event for " + invoiceUid));
    }

    private ArInvoice openItem(String salesInvoiceUid) {
        return arInvoices.findBySalesInvoiceUid(company.getId(), salesInvoiceUid)
                .orElseThrow(() -> new AssertionError("No AR open item for " + salesInvoiceUid));
    }

    /** AR subledger: what customers owe on open items, less credit not yet applied. */
    private BigDecimal arSubledger() {
        BigDecimal owed = arInvoices.findAll().stream()
                .filter(i -> company.getId().equals(i.getCompanyId()))
                .map(ArInvoice::getOutstandingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unapplied = arCreditNotes.findAll().stream()
                .filter(n -> company.getId().equals(n.getCompanyId()))
                .map(ArCreditNote::getUnappliedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return owed.subtract(unapplied);
    }

    /** Debit-normal balance (Σdebit − Σcredit) of one GL account. */
    private BigDecimal balance(Long accountId) {
        return tbByAccount().getOrDefault(accountId, BigDecimal.ZERO);
    }

    private Map<Long, BigDecimal> tbByAccount() {
        Map<Long, BigDecimal> tb = new HashMap<>();
        for (Object[] row : journalLines.trialBalanceSums(company.getId())) {
            BigDecimal dr = row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO;
            BigDecimal cr = row[2] != null ? (BigDecimal) row[2] : BigDecimal.ZERO;
            tb.put((Long) row[0], dr.subtract(cr));
        }
        return tb;
    }

    private void assertTrialBalanceBalanced() {
        BigDecimal net = tbByAccount().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).as("Σ debits − Σ credits across the ledger").isEqualByComparingTo("0");
    }

    private Long glAccountId(String code) {
        return glAccounts.findByCompanyIdAndAccountCode(company.getId(), code)
                .orElseThrow(() -> new AssertionError("GL account " + code + " missing"))
                .getId();
    }
}
