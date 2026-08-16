package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.fx.domain.entity.CurrencyRate;
import com.erp.modules.fx.repository.CurrencyRateRepository;
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
import com.erp.modules.sales.domain.dto.UpdateSalesSettingsRequest;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.common.money.FxRateNotFoundException;
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
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * T2b — FX posting integration test (ADR-0036 T2).
 *
 * <p>Proves three acceptance bars:
 * <ol>
 *   <li>Foreign USD invoice (rate 2500): {@code sales_invoices.fx_rate=2500},
 *       {@code base_gross_total_amount = face × 2500 HALF_UP}; the posted GL journal is
 *       BALANCED with every line in base currency (TZS) and the revenue/VAT/AR base legs
 *       match the converted base amounts (Σdebit == Σcredit).
 *   <li>Base-currency (TZS) invoice: {@code fx_rate=1}, {@code base_amount == face},
 *       journal byte-equivalent to pre-FX (no-regression guarantee, ADR-0036 D-8).
 *   <li>Unknown/inactive currency code rejected with {@link FxRateNotFoundException}
 *       or {@code IllegalArgumentException} per OQ-FX-06 (missing rate fails loudly).
 * </ol>
 *
 * <p>Setup mirrors {@code SalesPostingHandlerIT}: same {@link PostgresIntegrationTest} base,
 * same seeder calls, same event-dispatch pattern. The test seeds one USD→TZS rate row
 * (rate=2500) and one product at USD 1000 list price.
 */
class SalesInvoiceFxPostingIT extends PostgresIntegrationTest {

    // ---- GL / event infrastructure ----
    @Autowired private JournalEntryRepository journalEntryRepo;
    @Autowired private JournalLineRepository  journalLineRepo;
    @Autowired private DomainEventRepository  domainEventRepository;
    @Autowired private DomainEventDispatcher  dispatcher;
    @Autowired private ChartOfAccountService  chartOfAccountService;
    @Autowired private FiscalCalendarService  fiscalCalendarService;
    @Autowired private GlConfigService        glConfigService;

    // ---- Sales domain ----
    @Autowired private SalesInvoiceService    salesInvoiceService;
    @Autowired private SalesInvoiceRepository salesInvoiceRepo;
    @Autowired private TaxRateSeeder          taxRateSeeder;
    @Autowired private SalesSettingsService   salesSettingsService;

    // ---- Parties + Products ----
    @Autowired private CustomerService        customerService;
    @Autowired private AgentService           agentService;
    @Autowired private ProductService         productService;
    @Autowired private PriceListService       priceListService;
    @Autowired private UnitOfMeasureService   unitService;

    // ---- FX ----
    @Autowired private CurrencyRateRepository currencyRateRepo;

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

    private String customerUid;
    private String agentUid;
    private String pcsUid;
    private String priceListUid;
    /** Product priced at USD 1 000 (face). gross = 1 000 × 1.18 VAT = 1 180 USD face. */
    private String productUsdUid;
    /** Product priced at TZS 1 000. gross = 1 000 × 1.18 = 1 180 TZS (base-path regression). */
    private String productTzsUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("FX IT Org"));
        company = companies.save(new Company(org, "FXIT", "FX IT Co"));
        branch  = branches.save(new Branch(company, "FX1", "FX IT Branch"));

        AppUser root = new AppUser("fx_root", passwordEncoder.encode("RootPass1!"), "FX Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "fx_root", true, company.getId(), branch.getId(), null));

        // Sales prerequisites
        taxRateSeeder.seedDefaults(company.getId());

        // This suite is about FX stamping and GL posting, not stock — its products are
        // never received. NegativeStockGuard now reads a missing sales_settings row as BLOCK
        // (fail-safe), so opt this company into backorder deliberately.
        salesSettingsService.update(new UpdateSalesSettingsRequest(
                company.getUid(), false, null, "TZS", true));

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();

        priceListUid = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "STD", "Standard")).uid();

        // USD-priced product (STANDARD 18% VAT)
        productUsdUid = productService.create(new CreateProductRequest(
                company.getUid(), null, "USD Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD, null, null, null, null, null, null, null, null, null)).uid();
        productService.setPrice(productUsdUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "USD")));

        // TZS-priced product (STANDARD 18% VAT) — for the base-path no-regression test
        productTzsUid = productService.create(new CreateProductRequest(
                company.getUid(), null, "TZS Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD, null, null, null, null, null, null, null, null, null)).uid();
        productService.setPrice(productTzsUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "TZS")));

        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "FX Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).uid();

        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "FX Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();

        // GL prerequisites
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        // Seed USD→TZS rate: 1 USD = 2 500 TZS, effective today
        currencyRateRepo.save(new CurrencyRate(
                company.getId(), branch.getId(),
                "USD", "TZS",
                new BigDecimal("2500.00000000"),
                LocalDate.now(),
                "SPOT", "MANUAL",
                rootId));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Test 1: Foreign (USD) invoice — base triple stamped + balanced base GL
    // =========================================================================

    /**
     * USD invoice: price 1 000 USD + 18% VAT = 1 180 USD face.
     * Rate 2 500 → base amounts:
     *   baseNet  = round(1 000 × 2 500, 0) = 2 500 000 TZS
     *   baseVat  = round(180 × 2 500, 0)  = 450 000 TZS
     *   baseGross (plug) = 2 500 000 + 450 000 = 2 950 000 TZS
     *
     * Asserts on {@code sales_invoices}:
     *   fx_rate = 2 500, base_gross_total_amount = 2 950 000, rate_at IS NOT NULL
     *
     * Asserts on the GL journal:
     *   - every journal_line.currency == 'TZS' (base — BR-GL-06 gate passed)
     *   - Σdebit == Σcredit (sacred balance invariant)
     *   - DR Cash leg == 2 950 000 TZS
     *   - CR Revenue leg == 2 500 000 TZS
     *   - CR VAT leg == 450 000 TZS
     */
    @Test
    void foreignUsdInvoice_stampsBaseTriple_andPostsBalancedBaseCurrencyJournal() {
        // 1 × USD 1 000 product + 18% VAT = gross 1 180 USD
        SalesInvoiceDto invoice = createAndFinaliseInvoice("USD", productUsdUid, "1180");

        // --- Assert sales_invoices base triple ---
        SalesInvoice entity = salesInvoiceRepo.findByUid(invoice.uid())
                .orElseThrow(() -> new AssertionError("Invoice not found: " + invoice.uid()));

        assertThat(entity.getFxRate())
                .as("fx_rate must be 2500")
                .isEqualByComparingTo(new BigDecimal("2500"));

        // baseGross = 1000×2500 + 180×2500 = 2500000 + 450000 = 2950000 (TZS, scale 0)
        BigDecimal expectedBaseGross = new BigDecimal("2950000");
        assertThat(entity.getBaseGrossTotalAmount())
                .as("base_gross_total_amount must be face × rate = 2 950 000")
                .isEqualByComparingTo(expectedBaseGross);

        assertThat(entity.getRateAt())
                .as("rate_at must be set at finalise")
                .isNotNull();

        // --- Dispatch SALE.FINALISED → GL journal posted ---
        DomainEvent event = getPendingFinalisedEvent(invoice.uid());
        dispatcher.dispatchOne(event.getId());

        // Restore context after SYSTEM took over in the handler
        RequestContext.set(new RequestContext.Principal(
                rootId, "fx_root", true, company.getId(), branch.getId(), null));

        // --- Assert GL journal ---
        var entries = journalEntryRepo.findAll().stream()
                .filter(e -> invoice.uid().equals(e.getSourceRef()))
                .toList();
        assertThat(entries).as("exactly one GL entry for this invoice").hasSize(1);

        Long entryId = entries.get(0).getId();
        var lines = journalLineRepo.findByEntryIdOrderByLineNo(entryId);
        assertThat(lines).as("at least 2 lines (DR + CR)").hasSizeGreaterThanOrEqualTo(2);

        // Every line must be in base currency (TZS) — the sacred BR-GL-06 invariant
        lines.forEach(l ->
                assertThat(l.getCurrency())
                        .as("journal_line.currency must be base TZS (BR-GL-06)")
                        .isEqualTo("TZS")
        );

        // Σdebit == Σcredit in base
        BigDecimal sumDebit  = lines.stream()
                .map(l -> l.getDebitAmount()  != null ? l.getDebitAmount()  : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCredit = lines.stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(sumDebit)
                .as("GL journal must balance: Σdebit == Σcredit (sacred invariant)")
                .isEqualByComparingTo(sumCredit);

        // DR Cash (control/plug) == 2 950 000 TZS
        BigDecimal totalDebitBase = sumDebit;
        assertThat(totalDebitBase)
                .as("DR Cash leg must equal base gross = 2 950 000 TZS")
                .isEqualByComparingTo(new BigDecimal("2950000"));

        // CR Revenue == baseNet = 2 500 000 TZS
        BigDecimal expectedBaseNet = new BigDecimal("2500000");
        BigDecimal totalCreditBase = sumCredit;
        assertThat(totalCreditBase)
                .as("Σcredit (revenue + VAT) must equal 2 950 000 TZS")
                .isEqualByComparingTo(new BigDecimal("2950000"));

        // Find the revenue line (largest credit) and VAT line
        var creditLines = lines.stream()
                .filter(l -> l.getCreditAmount() != null && l.getCreditAmount().compareTo(BigDecimal.ZERO) > 0)
                .toList();
        assertThat(creditLines).as("at least 2 credit lines (revenue + VAT)").hasSizeGreaterThanOrEqualTo(2);

        BigDecimal maxCredit = creditLines.stream()
                .map(l -> l.getCreditAmount())
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        assertThat(maxCredit)
                .as("largest credit line (revenue) must be 2 500 000 TZS")
                .isEqualByComparingTo(expectedBaseNet);

        // VAT credit = 450 000 TZS
        BigDecimal expectedBaseVat = new BigDecimal("450000");
        BigDecimal vatCredit = creditLines.stream()
                .map(l -> l.getCreditAmount())
                .filter(c -> !c.equals(maxCredit))
                .findFirst()
                .orElse(BigDecimal.ZERO);
        assertThat(vatCredit)
                .as("VAT credit line must be 450 000 TZS")
                .isEqualByComparingTo(expectedBaseVat);
    }

    // =========================================================================
    // Test 2: Base-currency (TZS) invoice — fx_rate=1, base==face, no-regression
    // =========================================================================

    /**
     * TZS invoice: price 1 000 TZS + 18% VAT = 1 180 TZS gross.
     * Rate == 1 (identity short-circuit — no rate-table lookup).
     *
     * Asserts:
     *   - fx_rate = 1 on the invoice entity
     *   - base_gross_total_amount == gross_total_amount (face == base)
     *   - GL journal balanced with Σdebit == Σcredit == 1 180 TZS
     *   - all lines in TZS (byte-equivalent to pre-FX posting)
     */
    @Test
    void baseCurrencyTzsInvoice_fxRate1_baseEqualsface_noRegressionOnGlJournal() {
        // TZS product, 1 180 TZS gross (1 000 net + 180 VAT)
        SalesInvoiceDto invoice = createAndFinaliseInvoice("TZS", productTzsUid, "1180");

        // --- sales_invoices assertions ---
        SalesInvoice entity = salesInvoiceRepo.findByUid(invoice.uid())
                .orElseThrow(() -> new AssertionError("Invoice not found: " + invoice.uid()));

        assertThat(entity.getFxRate())
                .as("fx_rate must be 1 for base-currency invoice (identity short-circuit)")
                .isEqualByComparingTo(BigDecimal.ONE);

        assertThat(entity.getBaseGrossTotalAmount())
                .as("base_gross == face for base-currency invoice")
                .isEqualByComparingTo(entity.getGrossTotalAmount());

        assertThat(entity.getBaseGrossTotalAmount())
                .as("base_gross_total_amount = 1 180")
                .isEqualByComparingTo(new BigDecimal("1180"));

        // --- GL journal assertions ---
        DomainEvent event = getPendingFinalisedEvent(invoice.uid());
        dispatcher.dispatchOne(event.getId());

        RequestContext.set(new RequestContext.Principal(
                rootId, "fx_root", true, company.getId(), branch.getId(), null));

        var entries = journalEntryRepo.findAll().stream()
                .filter(e -> invoice.uid().equals(e.getSourceRef()))
                .toList();
        assertThat(entries).hasSize(1);

        Long entryId = entries.get(0).getId();
        var lines = journalLineRepo.findByEntryIdOrderByLineNo(entryId);

        // All lines in TZS
        lines.forEach(l ->
                assertThat(l.getCurrency())
                        .as("base-currency journal_line.currency must be TZS")
                        .isEqualTo("TZS")
        );

        BigDecimal sumDebit  = lines.stream()
                .map(l -> l.getDebitAmount()  != null ? l.getDebitAmount()  : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCredit = lines.stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(sumDebit)
                .as("GL must balance: Σdebit == Σcredit == 1 180 TZS")
                .isEqualByComparingTo(sumCredit);

        assertThat(sumDebit)
                .as("DR Cash must equal gross 1 180 TZS (pre-FX byte-equivalent)")
                .isEqualByComparingTo(new BigDecimal("1180"));
    }

    // =========================================================================
    // Test 3: Unknown/inactive currency → rejected on create (OQ-FX-06)
    // =========================================================================

    /**
     * A currency code with no active rate row for this company must fail loudly at finalise
     * (when CurrencyConversionService.toBase is called for the non-base foreign code).
     * Validates OQ-FX-06: "missing foreign rate must NEVER silently post at par."
     *
     * We create the invoice in "EUR" (no EUR→TZS rate seeded for this company in setUp)
     * and assert that finalise throws {@link FxRateNotFoundException}.
     */
    @Test
    void unknownCurrencyRate_rejected_oqFx06() {
        // EUR product (price in EUR — no EUR→TZS rate seeded)
        String productEurUid = productService.create(new CreateProductRequest(
                company.getUid(), null, "EUR Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD, null, null, null, null, null, null, null, null, null)).uid();
        productService.setPrice(productEurUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "EUR")));

        SalesInvoiceDto draft = salesInvoiceService.create(
                new CreateSalesInvoiceRequest(company.getUid(), customerUid, agentUid, "EUR", null, null));
        salesInvoiceService.addLine(draft.uid(),
                new AddInvoiceLineRequest(productEurUid, pcsUid, new BigDecimal("1"), null, null));
        salesInvoiceService.addPayment(draft.uid(),
                new AddPaymentRequest(TenderType.CASH, new BigDecimal("1180"), "EUR", null));

        // finalise must throw because there is no EUR→TZS rate for this company
        assertThatThrownBy(() ->
                salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest()))
                .isInstanceOf(FxRateNotFoundException.class)
                .hasMessageContaining("EUR")
                .hasMessageContaining("TZS");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private SalesInvoiceDto createAndFinaliseInvoice(String currency, String productUid,
                                                      String paymentAmount) {
        SalesInvoiceDto draft = salesInvoiceService.create(
                new CreateSalesInvoiceRequest(company.getUid(), customerUid, agentUid,
                        currency, null, null));
        salesInvoiceService.addLine(draft.uid(),
                new AddInvoiceLineRequest(productUid, pcsUid, new BigDecimal("1"), null, null));
        salesInvoiceService.addPayment(draft.uid(),
                new AddPaymentRequest(TenderType.CASH, new BigDecimal(paymentAmount), currency, null));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        return salesInvoiceService.getByUid(draft.uid());
    }

    private DomainEvent getPendingFinalisedEvent(String invoiceUid) {
        return domainEventRepository.findAll().stream()
                .filter(e -> DomainEventType.SALE_FINALISED.equals(e.getEventType())
                          && invoiceUid.equals(e.getAggregateUid()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No SALE.FINALISED event found for invoice uid=" + invoiceUid));
    }
}
