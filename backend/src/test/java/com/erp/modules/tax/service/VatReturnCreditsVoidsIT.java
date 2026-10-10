package com.erp.modules.tax.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ap.domain.dto.RaiseDebitNoteRequest;
import com.erp.modules.ap.service.ApDebitNoteService;
import com.erp.modules.ap.service.ApGlSeeder;
import com.erp.modules.ar.domain.dto.RaiseCreditNoteRequest;
import com.erp.modules.ar.service.ArCreditNoteService;
import com.erp.modules.ar.service.ArGlSeeder;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
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
import com.erp.modules.sales.domain.dto.VoidInvoiceRequest;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.modules.sales.service.SalesSettingsService;
import com.erp.modules.sales.service.TaxRateSeeder;
import com.erp.modules.tax.domain.dto.AddVatAdjustmentRequest;
import com.erp.modules.tax.domain.dto.FileVatReturnRequest;
import com.erp.modules.tax.domain.dto.OpenVatReturnRequest;
import com.erp.modules.tax.domain.dto.VatReturnDto;
import com.erp.modules.tax.domain.enums.VatAdjustmentReason;
import com.erp.modules.tax.domain.enums.VatAdjustmentSign;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * ACC-06 / ACC-24 — the VAT return ties to the ledger across credit notes, debit notes and a
 * cross-month void, and filing clears VAT Payable (2200) and VAT Input (1400) to zero.
 *
 * <p>Two consecutive months M1 (January) and M2 (February) of the current fiscal year:
 * <ul>
 *   <li>M1: invoices A and B (180 VAT each, finalised in M1), an AR credit note (90 VAT), a matched
 *       bill (360 input VAT) and an AP debit note (180 VAT).</li>
 *   <li>M2: invoice A is VOIDED (−180 output in M2), a second debit note (90 VAT) and, after M1 was
 *       filed, a credit note back-dated into M1 (18 VAT) carried on M2 as a CREDIT_NOTE_VAT
 *       adjustment.</li>
 * </ul>
 * Invoice timestamps are moved into M1/M2 with SQL — the computation windows read them; the GL
 * entries themselves keep their real dates, which is irrelevant to the all-time control balances
 * asserted here.
 */
class VatReturnCreditsVoidsIT extends PostgresIntegrationTest {

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2099-12-31T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired private SalesInvoiceService    salesInvoiceService;
    @Autowired private TaxRateSeeder          taxRateSeeder;
    @Autowired private SalesSettingsService   salesSettingsService;
    @Autowired private CustomerService        customerService;
    @Autowired private SupplierService        supplierService;
    @Autowired private AgentService           agentService;
    @Autowired private ProductService         productService;
    @Autowired private PriceListService       priceListService;
    @Autowired private UnitOfMeasureService   unitService;
    @Autowired private DomainEventRepository  domainEventRepository;
    @Autowired private DomainEventDispatcher  dispatcher;
    @Autowired private ArCreditNoteService    creditNotes;
    @Autowired private ApDebitNoteService     debitNotes;
    @Autowired private GLPostingService       glPosting;
    @Autowired private GLConfigResolver       glConfig;
    @Autowired private ChartOfAccountService  chartOfAccountService;
    @Autowired private FiscalCalendarService  fiscalCalendarService;
    @Autowired private GlConfigService        glConfigService;
    @Autowired private ApGlSeeder             apGlSeeder;
    @Autowired private ArGlSeeder             arGlSeeder;
    @Autowired private VatReturnService       vatReturnService;
    @Autowired private VatAdjustmentService   vatAdjustmentService;
    @Autowired private JdbcTemplate           jdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  companyUid;
    private String  walkInUid;
    private String  creditCustomerUid;
    private String  supplierUid;
    private Long    supplierId;
    private String  agentUid;
    private String  pcsUid;
    private String  productUid;
    private YearMonth m1;
    private YearMonth m2;
    private org.springframework.transaction.support.TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("VAT CNV IT Org"));
        company    = companies.save(new Company(org, "VATCV", "VAT CNV IT Co"));
        branch     = branches.save(new Branch(company, "VATCV1", "VAT CNV IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("vatcv_root", passwordEncoder.encode("VatCv0t1!xx"), "VAT CNV Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();
        asRoot();

        taxRateSeeder.seedDefaults(company.getId());
        salesSettingsService.update(new UpdateSalesSettingsRequest(
                company.getUid(), false, null, "TZS", true));
        pcsUid = unitService.create(new CreateUnitOfMeasureRequest(companyUid, "PCS", "Pieces")).uid();
        String priceListUid = priceListService.create(
                new CreatePriceListRequest(companyUid, "RETAIL", "Retail")).uid();
        productUid = productService.create(new CreateProductRequest(
                companyUid, null, "VAT CNV Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null)).uid();
        productService.setPrice(productUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "TZS")));

        walkInUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Walk-in",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).uid();
        creditCustomerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Credit Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).uid();
        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "VAT CNV Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();
        var sup = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "VAT CNV Supplier",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null));
        supplierUid = sup.uid();
        supplierId  = sup.id();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        apGlSeeder.seedDefaults(company.getId());
        arGlSeeder.seedDefaults(company.getId());

        int year = LocalDate.now().getYear();
        m1 = YearMonth.of(year, 1);
        m2 = YearMonth.of(year, 2);
        tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void creditNote_debitNote_andLaterMonthVoid_fileCorrectly_andClearControlAccounts() {
        // ---- M1 activity ----------------------------------------------------------------------
        String invA = finaliseInvoiceIn(m1.atDay(10));
        String invB = finaliseInvoiceIn(m1.atDay(12));
        creditNotes.raise(new RaiseCreditNoteRequest(companyUid, creditCustomerUid, null,
                m1.atDay(20), new BigDecimal("500"), new BigDecimal("90"), "TZS", "Price adjustment"));
        matchedBillWithGl(m1.atDay(8), new BigDecimal("2000"), new BigDecimal("360"));
        debitNotes.raise(new RaiseDebitNoteRequest(companyUid, supplierUid, null,
                m1.atDay(22), new BigDecimal("1000"), new BigDecimal("180"), "Short delivery", null));

        // ---- M2 activity: invoice A voided in M2, a second debit note ----------------------------
        salesInvoiceService.voidInvoice(invA, new VoidInvoiceRequest("Customer cancelled"));
        dispatch(DomainEventType.SALE_VOIDED, invA);
        jdbc.update("UPDATE sales_invoices SET voided_at = ? WHERE uid = ?",
                Timestamp.from(m2.atDay(5).atTime(9, 0).toInstant(ZoneOffset.UTC)), invA);
        debitNotes.raise(new RaiseDebitNoteRequest(companyUid, supplierUid, null,
                m2.atDay(14), new BigDecimal("500"), new BigDecimal("90"), "Damaged goods", null));

        // ---- M1 return: the later-voided invoice A stays in its original month (ACC-24) ----------
        VatReturnDto r1 = vatReturnService.open(
                new OpenVatReturnRequest(companyUid, m1.getYear(), m1.getMonthValue()));
        assertThat(r1.outputVat()).as("M1 output = 180 (A) + 180 (B) − 90 (credit note)")
                .isEqualByComparingTo("270");
        assertThat(r1.inputVat()).as("M1 input = 360 (bill) − 180 (debit note)")
                .isEqualByComparingTo("180");
        assertThat(r1.netVat()).isEqualByComparingTo("90");

        VatReturnDto filed1 = vatReturnService.file(r1.uid(),
                new FileVatReturnRequest("TRA-M1", m2.atDay(15)));
        assertThat(entryLeg(filed1.postedJournalUid(), "2200")).as("DR 2200").isEqualByComparingTo("270");
        assertThat(entryLeg(filed1.postedJournalUid(), "1400")).as("CR 1400").isEqualByComparingTo("-180");
        assertThat(entryLeg(filed1.postedJournalUid(), "2300")).as("CR 2300").isEqualByComparingTo("-90");

        // A credit note back-dated into the already-FILED M1: it debits 2200 but no open return
        // window will ever read it, so M2 carries it as a CREDIT_NOTE_VAT adjustment.
        creditNotes.raise(new RaiseCreditNoteRequest(companyUid, creditCustomerUid, null,
                m1.atDay(25), new BigDecimal("100"), new BigDecimal("18"), "TZS", "Late credit"));

        // ---- M2 return: the void and the debit note make both totals negative --------------------
        VatReturnDto r2 = vatReturnService.open(
                new OpenVatReturnRequest(companyUid, m2.getYear(), m2.getMonthValue()));
        vatAdjustmentService.addAdjustment(r2.uid(), new AddVatAdjustmentRequest(
                VatAdjustmentReason.CREDIT_NOTE_VAT, VatAdjustmentSign.DECREASE,
                new BigDecimal("18"), "Credit note dated in filed January"));
        r2 = vatReturnService.recompute(r2.uid());
        assertThat(r2.outputVat()).as("M2 output = −180 (void of A)").isEqualByComparingTo("-180");
        assertThat(r2.inputVat()).as("M2 input = −90 (debit note)").isEqualByComparingTo("-90");
        assertThat(r2.adjustmentsTotal()).isEqualByComparingTo("-18");
        assertThat(r2.netVat()).as("−180 − (−90) − 18").isEqualByComparingTo("-108");
        assertThat(r2.closingCredit()).isEqualByComparingTo("108");
        assertThat(r2.bands()).allSatisfy(b -> {
            assertThat(b.taxableBase().signum()).isGreaterThanOrEqualTo(0);
            assertThat(b.outputVat().signum()).isGreaterThanOrEqualTo(0);
        });

        VatReturnDto filed2 = vatReturnService.file(r2.uid(),
                new FileVatReturnRequest("TRA-M2", m2.plusMonths(1).atDay(15)));
        String e2 = filed2.postedJournalUid();
        assertThat(entryLeg(e2, "2200")).as("CR 2200 180 (void) + CR 18 (adjustment)")
                .isEqualByComparingTo("-198");
        assertThat(entryLeg(e2, "1400")).as("DR 1400 90").isEqualByComparingTo("90");
        assertThat(entryLeg(e2, "2300")).as("DR 2300 108 — a credit with TRA").isEqualByComparingTo("108");

        // ---- The ledger ties: control accounts clear, VAT Due = Σ filed net ---------------------
        assertThat(balance("2200")).as("VAT Payable clears to zero").isEqualByComparingTo("0");
        assertThat(balance("1400")).as("VAT Input clears to zero").isEqualByComparingTo("0");
        assertThat(balance("2300").negate())
                .as("VAT Due (credit-positive) = 90 + (−108)")
                .isEqualByComparingTo(filed1.netVat().add(filed2.netVat()));

        // The filed M1 return is untouched by everything that happened after it.
        VatReturnDto m1Again = vatReturnService.getByUid(filed1.uid());
        assertThat(m1Again.outputVat()).isEqualByComparingTo("270");
        assertThat(m1Again.netVat()).isEqualByComparingTo("90");
        assertThat(invB).isNotBlank();
    }

    // -------------------------------------------------------------------------

    private void asRoot() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "vatcv_root", true, company.getId(), branch.getId(), null));
    }

    /** 1 × 1000 + 18% = 1180 cash invoice, finalised, posted, and its timestamp moved to {@code day}. */
    private String finaliseInvoiceIn(LocalDate day) {
        SalesInvoiceDto draft = salesInvoiceService.create(
                new CreateSalesInvoiceRequest(companyUid, walkInUid, agentUid, "TZS", null, null));
        salesInvoiceService.addLine(draft.uid(), new AddInvoiceLineRequest(
                productUid, pcsUid, BigDecimal.ONE, null, null));
        salesInvoiceService.addPayment(draft.uid(), new AddPaymentRequest(
                TenderType.CASH, new BigDecimal("1180"), "TZS", null));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatch(DomainEventType.SALE_FINALISED, draft.uid());
        jdbc.update("UPDATE sales_invoices SET finalised_at = ? WHERE uid = ?",
                Timestamp.from(day.atTime(10, 0).toInstant(ZoneOffset.UTC)), draft.uid());
        return draft.uid();
    }

    private void dispatch(String type, String aggregateUid) {
        List<DomainEvent> events = domainEventRepository.findAll().stream()
                .filter(e -> type.equals(e.getEventType()) && aggregateUid.equals(e.getAggregateUid()))
                .toList();
        assertThat(events).as(type + " event for " + aggregateUid).isNotEmpty();
        events.forEach(e -> dispatcher.dispatchOne(e.getId()));
        asRoot();
    }

    /** A MATCHED bill row plus the journal bill-match would post (DR Purchases + DR 1400 / CR AP). */
    private void matchedBillWithGl(LocalDate billDate, BigDecimal net, BigDecimal vat) {
        BigDecimal gross = net.add(vat);
        int k = SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO supplier_bills (uid, company_id, branch_id, supplier_id, bill_number,
                    supplier_invoice_no, source, bill_date, due_date, net_amount, vat_amount,
                    gross_amount, outstanding_amount, currency, status, fx_rate, base_gross_amount)
                VALUES (?, ?, ?, ?, ?, ?, 'BILL', ?, ?, ?, ?, ?, ?, 'TZS', 'MATCHED', 1, ?)
                """,
                String.format("VCV%023d", k), company.getId(), branch.getId(), supplierId,
                "VCV-BILL-" + k, "VCV-INV-" + k, billDate, billDate.plusDays(30),
                net, vat, gross, gross, gross);
        Long cid = company.getId();
        tx.executeWithoutResult(st -> glPosting.post(new JournalEntryDraft(cid, branch.getId(), billDate, "Bill VCV-" + k,
                JournalSourceType.AP_BILL, "VCV-BILL-" + k, null, rootId, List.of(
                new LineDraft(glConfig.resolve(cid, GlConfigKey.PURCHASES).getId(),
                        net, BigDecimal.ZERO, "TZS", "net"),
                new LineDraft(glConfig.resolve(cid, GlConfigKey.VAT_INPUT).getId(),
                        vat, BigDecimal.ZERO, "TZS", "vat"),
                new LineDraft(glConfig.resolve(cid, GlConfigKey.ACCOUNTS_PAYABLE).getId(),
                        BigDecimal.ZERO, gross, "TZS", "ap")))));
    }

    /** All-time debit-minus-credit on an account code. */
    private BigDecimal balance(String code) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(jl.debit_amount - jl.credit_amount), 0)
                FROM journal_lines jl JOIN chart_of_accounts a ON a.id = jl.account_id
                WHERE jl.company_id = ? AND a.account_code = ?
                """, BigDecimal.class, company.getId(), code);
    }

    /** Debit-minus-credit of one journal entry on an account code. */
    private BigDecimal entryLeg(String entryUid, String code) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(jl.debit_amount - jl.credit_amount), 0)
                FROM journal_lines jl
                JOIN journal_entries je ON je.id = jl.entry_id
                JOIN chart_of_accounts a ON a.id = jl.account_id
                WHERE je.uid = ? AND a.account_code = ?
                """, BigDecimal.class, entryUid, code);
    }
}
