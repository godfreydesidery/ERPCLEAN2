package com.erp.modules.tax.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.fx.domain.entity.CurrencyRate;
import com.erp.modules.fx.repository.CurrencyRateRepository;
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
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.service.AgentService;
import com.erp.modules.parties.service.CustomerService;
import com.erp.modules.parties.service.SupplierService;
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
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.modules.sales.service.SalesSettingsService;
import com.erp.modules.sales.service.TaxRateSeeder;
import com.erp.modules.tax.domain.dto.OpenVatReturnRequest;
import com.erp.modules.tax.domain.dto.VatReturnBandDto;
import com.erp.modules.tax.domain.dto.VatReturnDto;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Live defect: the VAT return added foreign-currency VAT unconverted. Five USD invoices carrying
 * USD 10.16 of VAT were summed into a TZS return as TZS 10.16, so output VAT fell short of the
 * base-currency Sales Summary (and of the GL VAT Payable account) by 10.16 × 2500 − 10.16.
 *
 * <p>Bars, on real Postgres, for a TZS-base company with one TZS and one USD sale plus one TZS
 * and one USD supplier bill in the same month, at a fractional USD rate so rounding is exercised:
 * <ol>
 *   <li>output VAT == Σ per-document round(VAT × stamped fx_rate) == the GL VAT Payable (2200)
 *       credit movement the sales postings made for the period;
 *   <li>the STANDARD band and the sales turnover are in base too;
 *   <li>input VAT == Σ per-bill round(vat_amount × stamped fx_rate) == what bill-match would debit
 *       to VAT_INPUT;
 *   <li>recompute is idempotent.
 * </ol>
 */
class VatReturnBaseCurrencyIT extends PostgresIntegrationTest {

    @Autowired private SalesInvoiceService    salesInvoiceService;
    @Autowired private TaxRateSeeder          taxRateSeeder;
    @Autowired private SalesSettingsService   salesSettingsService;
    @Autowired private CustomerService        customerService;
    @Autowired private AgentService           agentService;
    @Autowired private SupplierService        supplierService;
    @Autowired private ProductService         productService;
    @Autowired private PriceListService       priceListService;
    @Autowired private UnitOfMeasureService   unitService;
    @Autowired private DomainEventRepository  domainEventRepository;
    @Autowired private DomainEventDispatcher  dispatcher;
    @Autowired private CurrencyRateRepository currencyRateRepo;
    @Autowired private ChartOfAccountService  chartOfAccountService;
    @Autowired private FiscalCalendarService  fiscalCalendarService;
    @Autowired private GlConfigService        glConfigService;
    @Autowired private VatReturnService       vatReturnService;
    @Autowired private JdbcTemplate           jdbc;

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    /** A fractional rate so every conversion has to round to whole shillings. */
    private static final BigDecimal USD_RATE = new BigDecimal("2512.34000000");

    private final AtomicInteger seq = new AtomicInteger();

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  companyUid;
    private String  customerUid;
    private String  agentUid;
    private String  pcsUid;
    private Long    supplierId;
    private String  productTzsUid;
    private String  productUsdUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("VAT Base IT Org"));
        company    = companies.save(new Company(org, "VATBC", "VAT Base IT Co"));
        branch     = branches.save(new Branch(company, "VATBC1", "VAT Base IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("vatbc_root", passwordEncoder.encode("VatBc0t1!"), "VAT Base Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root   = users.save(root);
        rootId = root.getId();
        asRoot();

        taxRateSeeder.seedDefaults(company.getId());
        salesSettingsService.update(new UpdateSalesSettingsRequest(
                company.getUid(), false, null, "TZS", true));

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(companyUid, "PCS", "Pieces")).uid();
        String priceListUid = priceListService.create(
                new CreatePriceListRequest(companyUid, "RETAIL", "Retail")).uid();

        // TZS 1 000 + 18% = VAT 180
        productTzsUid = productService.create(new CreateProductRequest(
                companyUid, null, "TZS Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null)).uid();
        productService.setPrice(productTzsUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "TZS")));

        // USD 12.00 + 18% = VAT 2.16 (the live document's figure)
        productUsdUid = productService.create(new CreateProductRequest(
                companyUid, null, "USD Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null)).uid();
        productService.setPrice(productUsdUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto("12.00", "USD")));

        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "VAT Base Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).uid();
        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "VAT Base Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();
        supplierId = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "VAT Base Supplier",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null)).id();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        currencyRateRepo.save(new CurrencyRate(
                company.getId(), branch.getId(), "USD", "TZS", USD_RATE,
                LocalDate.now(), "SPOT", "MANUAL", rootId));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void outputAndInputVat_areTotalledInBaseCurrency_andMatchTheGl_andRecomputeIsIdempotent() {
        finaliseAndPost("TZS", productTzsUid, "1180");
        finaliseAndPost("USD", productUsdUid, "14.16");

        // Supplier bills already matched (MATCHED stamps fx_rate): TZS VAT 360 and USD VAT 9.00.
        insertMatchedBill("TZS", BigDecimal.ONE, "2000", "360");
        insertMatchedBill("USD", USD_RATE, "50.00", "9.00");

        YearMonth now = YearMonth.now();
        VatReturnDto opened = vatReturnService.open(
                new OpenVatReturnRequest(companyUid, now.getYear(), now.getMonthValue()));

        // TZS 180 + round(2.16 × 2512.34 = 5 426.6544) = 5 427  →  5 607 (was 182.16)
        BigDecimal expectedOutput = new BigDecimal("5607");
        // TZS 360 + round(9.00 × 2512.34 = 22 611.06) = 22 611  →  22 971 (was 369.00)
        BigDecimal expectedInput  = new BigDecimal("22971");
        // net base: 1 000 + round(12.00 × 2512.34 = 30 148.08) = 30 148  →  31 148
        BigDecimal expectedTurnover = new BigDecimal("31148");

        BigDecimal glVatPayable = glVatPayableCreditMovement(now);
        assertThat(glVatPayable).as("GL VAT Payable credit movement for the month")
                .isEqualByComparingTo(expectedOutput);

        assertThat(opened.outputVat()).as("output VAT in base == GL VAT payable movement")
                .isEqualByComparingTo(glVatPayable);
        assertThat(opened.inputVat()).as("input VAT in base")
                .isEqualByComparingTo(expectedInput);
        assertThat(opened.salesTurnover()).as("sales turnover in base")
                .isEqualByComparingTo(expectedTurnover);
        VatReturnBandDto standard = band(opened, "STANDARD");
        assertThat(standard.outputVat()).isEqualByComparingTo(expectedOutput);
        assertThat(standard.taxableBase()).isEqualByComparingTo(expectedTurnover);
        assertThat(opened.netVat()).as("net = output − input")
                .isEqualByComparingTo(expectedOutput.subtract(expectedInput));

        // Recompute twice: identical figures, same band layout.
        VatReturnDto first  = vatReturnService.recompute(opened.uid());
        VatReturnDto second = vatReturnService.recompute(opened.uid());
        for (VatReturnDto r : new VatReturnDto[] {first, second}) {
            assertThat(r.outputVat()).isEqualByComparingTo(expectedOutput);
            assertThat(r.inputVat()).isEqualByComparingTo(expectedInput);
            assertThat(r.netVat()).isEqualByComparingTo(opened.netVat());
            assertThat(r.salesTurnover()).isEqualByComparingTo(expectedTurnover);
            assertThat(r.bands()).hasSize(opened.bands().size());
            assertThat(band(r, "STANDARD").outputVat()).isEqualByComparingTo(expectedOutput);
        }
    }

    @Test
    void baseCurrencyOnlyCompany_figuresAreTheFaceAmounts() {
        finaliseAndPost("TZS", productTzsUid, "1180");
        insertMatchedBill("TZS", BigDecimal.ONE, "2000", "360");

        YearMonth now = YearMonth.now();
        VatReturnDto r = vatReturnService.open(
                new OpenVatReturnRequest(companyUid, now.getYear(), now.getMonthValue()));

        assertThat(r.outputVat()).isEqualByComparingTo("180");
        assertThat(r.inputVat()).isEqualByComparingTo("360");
        assertThat(r.salesTurnover()).isEqualByComparingTo("1000");
        assertThat(band(r, "STANDARD").outputVat()).isEqualByComparingTo("180");
        assertThat(r.outputVat()).isEqualByComparingTo(glVatPayableCreditMovement(now));
    }

    // -------------------------------------------------------------------------

    private void asRoot() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "vatbc_root", true, company.getId(), branch.getId(), null));
    }

    private void finaliseAndPost(String currency, String productUid, String payment) {
        SalesInvoiceDto draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                companyUid, customerUid, agentUid, currency, null, null));
        salesInvoiceService.addLine(draft.uid(),
                new AddInvoiceLineRequest(productUid, pcsUid, BigDecimal.ONE, null, null));
        salesInvoiceService.addPayment(draft.uid(),
                new AddPaymentRequest(TenderType.CASH, new BigDecimal(payment), currency, null));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        var event = domainEventRepository.findAll().stream()
                .filter(e -> DomainEventType.SALE_FINALISED.equals(e.getEventType())
                        && draft.uid().equals(e.getAggregateUid()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no SALE.FINALISED for " + draft.uid()));
        dispatcher.dispatchOne(event.getId());
        asRoot();
    }

    private void insertMatchedBill(String currency, BigDecimal rate, String net, String vat) {
        BigDecimal n = new BigDecimal(net);
        BigDecimal v = new BigDecimal(vat);
        BigDecimal gross = n.add(v);
        int k = seq.incrementAndGet();
        LocalDate today = LocalDate.now();
        jdbc.update("""
                INSERT INTO supplier_bills (uid, company_id, branch_id, supplier_id, bill_number,
                    supplier_invoice_no, source, bill_date, due_date, net_amount, vat_amount,
                    gross_amount, outstanding_amount, currency, status, fx_rate, base_gross_amount)
                VALUES (?, ?, ?, ?, ?, ?, 'BILL', ?, ?, ?, ?, ?, ?, ?, 'MATCHED', ?, ?)
                """,
                String.format("VBC%023d", k), company.getId(), branch.getId(), supplierId,
                "VBC-BILL-" + k, "VBC-INV-" + k, today, today.plusDays(30), n, v, gross, gross,
                currency, rate, gross.multiply(rate).setScale(0, java.math.RoundingMode.HALF_UP));
    }

    /** Net credit on the VAT Payable control account (2200) posted by SALES journals this month. */
    private BigDecimal glVatPayableCreditMovement(YearMonth ym) {
        BigDecimal v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(jl.credit_amount - jl.debit_amount), 0)
                FROM journal_lines jl
                JOIN journal_entries je ON je.id = jl.entry_id
                JOIN chart_of_accounts a ON a.id = jl.account_id
                WHERE je.company_id = ? AND a.account_code = '2200'
                  AND je.source_type = 'SALES'
                  AND je.posting_date BETWEEN ? AND ?
                """, BigDecimal.class, company.getId(), ym.atDay(1), ym.atEndOfMonth());
        return v != null ? v : BigDecimal.ZERO;
    }

    private static VatReturnBandDto band(VatReturnDto r, String key) {
        return r.bands().stream().filter(b -> key.equals(b.taxBand())).findFirst()
                .orElseThrow(() -> new AssertionError("no band " + key));
    }
}
