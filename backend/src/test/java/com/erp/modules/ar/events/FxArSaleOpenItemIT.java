package com.erp.modules.ar.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.ar.service.ArReceiptService;
import com.erp.modules.ar.domain.dto.ArReceiptDto;
import com.erp.modules.ar.domain.dto.RecordReceiptRequest;
import com.erp.modules.fx.domain.dto.UpsertRateRequest;
import com.erp.modules.fx.repository.CurrencyRateRepository;
import com.erp.modules.fx.domain.entity.CurrencyRate;
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
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.UpdateSalesSettingsRequest;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.modules.sales.service.SalesSettingsService;
import com.erp.modules.sales.service.TaxRateSeeder;
import com.erp.modules.cashbank.service.CashBankSeeder;
import com.erp.platform.common.money.FxRateService;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Fix-A regression IT: foreign credit-sale AR open item is FX-stamped by ArSalePostedHandler
 * (ADR-0036 findings #2/#3/#7/#8).
 *
 * <p>Drives the REAL path — SalesInvoiceService.finalise → SALE.FINALISED event →
 * DomainEventDispatcher → ArSalePostedHandler.createOpenItemIfCredit — with NO raw-SQL bypass.
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>Foreign (USD) credit sale → open item carries the invoice fx_rate and base amounts
 *       (I-2/I-3/I-4 correctness guard).</li>
 *   <li>baseOriginalAmount == baseOutstandingAmount == round(face × fxRate) at TZS minor units
 *       (TZS=0 → no decimals).</li>
 *   <li>Receipt at a DIFFERENT settlement rate → realized FX = (settlementRate − invoiceRate) ×
 *       relieved face; AR control CRs exactly the base originally debited; GL is balanced (I-1/I-3).
 *   </li>
 *   <li>Base-currency (TZS) credit sale → no-regression: open item persists byte-identically
 *       to pre-FX (fxRate=1, base=face, I-5).</li>
 * </ol>
 */
class FxArSaleOpenItemIT extends PostgresIntegrationTest {

    @Autowired private SalesInvoiceService    salesInvoiceService;
    @Autowired private ArReceiptService       receiptService;
    @Autowired private FxRateService          fxRateService;
    @Autowired private TaxRateSeeder          taxRateSeeder;
    @Autowired private SalesSettingsService   salesSettingsService;
    @Autowired private CustomerService        customerService;
    @Autowired private AgentService           agentService;
    @Autowired private ProductService         productService;
    @Autowired private PriceListService       priceListService;
    @Autowired private UnitOfMeasureService   unitService;
    @Autowired private DomainEventRepository  domainEventRepo;
    @Autowired private DomainEventDispatcher  dispatcher;
    @Autowired private ArInvoiceRepository    arInvoices;
    @Autowired private ChartOfAccountRepository accountRepo;
    @Autowired private JournalEntryRepository journalEntryRepo;
    @Autowired private JournalLineRepository  journalLineRepo;
    @Autowired private CurrencyRateRepository rateRepo;
    @Autowired private ChartOfAccountService  chartOfAccountService;
    @Autowired private FiscalCalendarService  fiscalCalendarService;
    @Autowired private GlConfigService        glConfigService;
    @Autowired private CashBankSeeder         cashBankSeeder;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    private static final String USD = "USD";
    private static final String TZS = "TZS";

    /** Invoice rate: 1 USD = TZS 2,500 */
    private static final BigDecimal RATE_INVOICE    = new BigDecimal("2500.00000000");
    /** Settlement rate (gain): 1 USD = TZS 2,600 */
    private static final BigDecimal RATE_SETTLEMENT = new BigDecimal("2600.00000000");

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  creditCustomerUid;
    private String  agentUid;
    private String  pcsUid;
    private String  priceListUid;
    private String  productUsdUid;  // priced in USD
    private String  productTzsUid;  // priced in TZS (no-regression)

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("FxSale IT Org"));
        company = companies.save(new Company(org, "FXSALE", "FxSale IT Co"));
        branch  = branches.save(new Branch(company, "FXSALE1", "FxSale IT Branch"));

        AppUser root = new AppUser("fxsale_root", passwordEncoder.encode("FxSaleR00t!"), "FxSale Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "fxsale_root", true, company.getId(), branch.getId(), null));

        taxRateSeeder.seedDefaults(company.getId());

        // This suite is about FX AR open items, not stock — its products are
        // never received. NegativeStockGuard now reads a missing sales_settings row as BLOCK
        // (fail-safe), so opt this company into backorder deliberately.
        salesSettingsService.update(new UpdateSalesSettingsRequest(
                company.getUid(), false, null, "TZS", true));
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        cashBankSeeder.seedDefaults(company.getId());

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        priceListUid = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();

        // USD product: 1 unit = USD 100 (no VAT for simplicity — use VatStatus.EXEMPT)
        productUsdUid = productService.create(new CreateProductRequest(
                company.getUid(), null, "USD Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.EXEMPT, null, null, null, null, null, null, null, null, null)).uid();
        productService.setPrice(productUsdUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto("100", USD)));

        // TZS product: 1 unit = TZS 1000 (no-regression check)
        productTzsUid = productService.create(new CreateProductRequest(
                company.getUid(), null, "TZS Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.EXEMPT, null, null, null, null, null, null, null, null, null)).uid();
        productService.setPrice(productTzsUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", TZS)));

        creditCustomerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "FX Credit Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).uid();

        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "FX Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();

        // Seed USD → TZS rate at RATE_INVOICE for today
        rateRepo.save(new CurrencyRate(
                company.getId(), branch.getId(),
                USD, TZS, RATE_INVOICE, LocalDate.now(), "SPOT", "IT-seed", rootId));

        // Seed settlement rate (different) for the receipt date (today as well, higher row wins)
        rateRepo.save(new CurrencyRate(
                company.getId(), branch.getId(),
                USD, TZS, RATE_SETTLEMENT, LocalDate.now().plusDays(1), "SPOT", "IT-seed", rootId));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1 + 2: Foreign (USD) credit sale → open item FX-stamped + base correct
    // =========================================================================

    @Test
    void foreignCreditSale_openItemCarriesFxTriple() {
        // Create and finalise a USD credit invoice: 1 × USD 100 = USD 100 face
        SalesInvoiceDto invoice = finaliseCreditInvoice(productUsdUid, USD, new BigDecimal("1"));

        dispatch(invoice.uid());

        ArInvoice openItem = requireOpenItem(invoice.uid());

        // fx_rate must equal the rate stamped on the invoice at finalise (RATE_INVOICE = 2500)
        assertThat(openItem.getFxRate())
                .as("AR open item fx_rate must equal the invoice rate stamped at finalise")
                .isEqualByComparingTo(RATE_INVOICE);

        // base = round(face × rate) at TZS minor units (0 → integer)
        BigDecimal expectedBase = openItem.getOutstandingAmount()
                .multiply(RATE_INVOICE)
                .setScale(0, RoundingMode.HALF_UP);
        assertThat(openItem.getBaseOriginalAmount())
                .as("baseOriginalAmount must equal round(face × fxRate) at TZS minor units (0 dp)")
                .isEqualByComparingTo(expectedBase);
        assertThat(openItem.getBaseOutstandingAmount())
                .as("baseOutstandingAmount must equal baseOriginalAmount on a fresh open item")
                .isEqualByComparingTo(expectedBase);
        assertThat(openItem.getRateAt())
                .as("rateAt must be non-null on a stamped open item")
                .isNotNull();
    }

    // =========================================================================
    // Bar 3: Receipt at different rate → realized FX == (settlementRate − invoiceRate) × face;
    //        AR control nets to zero across the lifecycle; GL balanced (I-1/I-3)
    // =========================================================================

    @Test
    void foreignCreditSale_receipt_realizedFxCorrect_arControlNetsZero() {
        // USD 100 face @ invoice rate 2500 → base AR DR = TZS 250,000
        SalesInvoiceDto invoice = finaliseCreditInvoice(productUsdUid, USD, new BigDecimal("1"));
        dispatch(invoice.uid());
        ArInvoice openItem = requireOpenItem(invoice.uid());
        BigDecimal faceUsd = openItem.getOutstandingAmount();
        // Expect base original = USD 100 × 2500 = TZS 250,000
        BigDecimal expectedBaseOriginal = faceUsd.multiply(RATE_INVOICE)
                .setScale(0, RoundingMode.HALF_UP);
        assertThat(openItem.getBaseOriginalAmount()).isEqualByComparingTo(expectedBaseOriginal);

        // Settle at RATE_SETTLEMENT (2600) — settlement date is tomorrow (the higher rate)
        ArReceiptDto receipt = receiptService.recordAndAllocate(new RecordReceiptRequest(
                company.getUid(), creditCustomerUid,
                faceUsd, USD, LocalDate.now().plusDays(1), "BANK_TRANSFER", "FX-RCPT-001",
                List.of()));

        // Invoice must be PAID
        ArInvoice settled = arInvoices.findByUid(openItem.getUid()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(ArInvoiceStatus.PAID);
        assertThat(settled.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        // GL journal must be balanced (I-1)
        assertThat(receipt.glEntryUid()).isNotBlank();
        var entry = journalEntryRepo.findByUid(receipt.glEntryUid()).orElseThrow();
        var glLines = journalLineRepo.findByEntryIdOrderByLineNo(entry.getId());

        BigDecimal sumDr = glLines.stream()
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCr = glLines.stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumDr)
                .as("GL journal must balance (Σdebit == Σcredit, I-1)")
                .isEqualByComparingTo(sumCr);

        // Realized FX gain = (2600 - 2500) × 100 = TZS 10,000 (I-3)
        BigDecimal expectedFxGain = RATE_SETTLEMENT.subtract(RATE_INVOICE)
                .multiply(faceUsd)
                .setScale(0, RoundingMode.HALF_UP);
        Long fxGainAccountId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "4920")
                .map(ChartOfAccount::getId).orElseThrow();
        BigDecimal actualFxGain = glLines.stream()
                .filter(l -> fxGainAccountId.equals(l.getAccountId()))
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(actualFxGain)
                .as("Realized FX gain must equal (settlementRate - invoiceRate) × face (I-3)")
                .isEqualByComparingTo(expectedFxGain);

        // AR control CR (account 1200) must equal the base originally debited = TZS 250,000
        Long arAccountId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "1200")
                .map(ChartOfAccount::getId).orElseThrow();
        BigDecimal arCr = glLines.stream()
                .filter(l -> arAccountId.equals(l.getAccountId()))
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(arCr)
                .as("AR control CR must equal the original invoice base (AR control nets to zero, I-3)")
                .isEqualByComparingTo(expectedBaseOriginal);
    }

    // =========================================================================
    // Bar 4: Base-currency (TZS) credit sale — no-regression (I-5)
    // =========================================================================

    @Test
    void baseCurrencyCreditSale_noRegression_fxRateOneBaseEqualsface() {
        // TZS credit sale: 1 × TZS 1000
        SalesInvoiceDto invoice = finaliseCreditInvoice(productTzsUid, TZS, new BigDecimal("1"));
        dispatch(invoice.uid());

        ArInvoice openItem = requireOpenItem(invoice.uid());

        // fxRate must be 1 (identity path — base == face)
        assertThat(openItem.getFxRate())
                .as("TZS credit sale fxRate must be 1 (I-5 no-regression)")
                .isEqualByComparingTo(BigDecimal.ONE);
        assertThat(openItem.getBaseOriginalAmount())
                .as("TZS credit sale baseOriginalAmount must equal face (I-5 no-regression)")
                .isEqualByComparingTo(openItem.getOriginalAmount());
        assertThat(openItem.getBaseOutstandingAmount())
                .as("TZS credit sale baseOutstandingAmount must equal face")
                .isEqualByComparingTo(openItem.getOutstandingAmount());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Creates and finalises a credit invoice for the given product/currency/qty.
     * No payment is tendered — the service must accept credit-account customers without a
     * full-payment requirement (ADR-0014 D-10).
     */
    private SalesInvoiceDto finaliseCreditInvoice(String productUid, String currency, BigDecimal qty) {
        SalesInvoiceDto draft = salesInvoiceService.create(
                new CreateSalesInvoiceRequest(
                        company.getUid(), creditCustomerUid, agentUid, currency, null, null));
        salesInvoiceService.addLine(draft.uid(),
                new AddInvoiceLineRequest(productUid, pcsUid, qty, null, null));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        return salesInvoiceService.getByUid(draft.uid());
    }

    private void dispatch(String invoiceUid) {
        DomainEvent event = domainEventRepo.findAll().stream()
                .filter(e -> DomainEventType.SALE_FINALISED.equals(e.getEventType())
                          && invoiceUid.equals(e.getAggregateUid()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No SALE.FINALISED event found for invoice " + invoiceUid));
        dispatcher.dispatchOne(event.getId());
    }

    private ArInvoice requireOpenItem(String invoiceUid) {
        return arInvoices.findAll().stream()
                .filter(i -> invoiceUid.equals(i.getSourceInvoiceUid()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No AR open item found for invoice " + invoiceUid));
    }
}
