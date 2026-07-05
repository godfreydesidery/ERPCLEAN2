package com.erp.modules.gl.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.gl.domain.dto.TrialBalanceDto;
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
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.VoidInvoiceRequest;
import com.erp.modules.sales.service.SalesInvoiceService;
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
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Acceptance-bar integration tests for the GL SalesPostingHandler + SaleVoidingHandler (ADR-0013
 * D-6, FR-GL-10/12/13, BR-GL-09/11).
 *
 * <p>Acceptance bar (per spec):
 * <ol>
 *   <li>finalise → SALE.FINALISED → dispatcher → balanced JE exists in journal_lines (DR == CR).
 *   <li>Trial balance (all periods) nets to zero (totalDebits == totalCredits).
 *   <li>void → SALE.VOIDED → dispatcher → SALES_REVERSAL entry exists; TB still nets to zero.
 *   <li>Idempotency: double-dispatch of the same event is a no-op (one entry, not two).
 * </ol>
 *
 * <p>VAT rate: uses the default 18% seed. Product price 1 000 TZS.
 * Gross = 1 000 × 1.18 = 1 180 TZS. DR Cash 1 180; CR Revenue 1 000; CR VAT 180.
 */
class SalesPostingHandlerIT extends PostgresIntegrationTest {

    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private TaxRateSeeder taxRateSeeder;
    @Autowired private CustomerService customerService;
    @Autowired private AgentService agentService;
    @Autowired private ProductService productService;
    @Autowired private PriceListService priceListService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private DomainEventDispatcher dispatcher;
    @Autowired private JournalEntryRepository journalEntryRepo;
    @Autowired private JournalLineRepository journalLineRepo;
    @Autowired private TrialBalanceQuery trialBalanceQuery;
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private GlConfigService glConfigService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private Branch branch;
    private Long rootId;
    private String customerUid;
    private String agentUid;
    private String pcsUid;
    private String priceListUid;
    private String productUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("GL Handler IT Org"));
        company = companies.save(new Company(org, "GLHIT", "GL Handler IT Co"));
        branch  = branches.save(new Branch(company, "GLH1", "GL Handler IT Branch"));

        AppUser root = new AppUser("gl_root", passwordEncoder.encode("RootPass1!"), "GL Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "gl_root", true, company.getId(), branch.getId(), null));

        // Sales prerequisites
        taxRateSeeder.seedDefaults(company.getId());

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();

        priceListUid = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();

        productUid = productService.create(new CreateProductRequest(
                company.getUid(), null, "GL Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD, null, null, null, null, null, null, null, null, null)).uid();
        productService.setPrice(productUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "TZS")));

        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "GL Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).uid();

        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "GL Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();

        // GL prerequisites — CoA, fiscal year, configs
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1 + 2: finalise → balanced JE; TB nets to zero
    // =========================================================================

    @Test
    void finalise_producesBalancedJournalEntry() {
        SalesInvoiceDto invoice = createAndFinaliseInvoice();

        // Drive the SALE.FINALISED event through the handler
        DomainEvent event = getPendingFinalisedEvent(invoice.uid());
        dispatcher.dispatchOne(event.getId());

        // One journal entry for the invoice (source_ref = invoice uid)
        var entries = journalEntryRepo.findAll().stream()
                .filter(e -> invoice.uid().equals(e.getSourceRef()))
                .toList();
        assertThat(entries).hasSize(1);

        // Lines must balance: sum(debit) == sum(credit)
        Long entryId = entries.get(0).getId();
        var lines = journalLineRepo.findByEntryIdOrderByLineNo(entryId);
        assertThat(lines).hasSizeGreaterThanOrEqualTo(2); // ≥ DR Cash + CR Revenue (+ optional CR VAT)

        BigDecimal sumDebit  = lines.stream()
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCredit = lines.stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(sumDebit).isEqualByComparingTo(sumCredit)
                .as("journal entry must be balanced: DR == CR");
    }

    @Test
    void finalise_trialBalanceNetsToZero() {
        SalesInvoiceDto invoice = createAndFinaliseInvoice();
        dispatcher.dispatchOne(getPendingFinalisedEvent(invoice.uid()).getId());

        // Restore context after SYSTEM took over in the handler (handler restores it, but REQUIRES_NEW boundary resets ThreadLocal)
        RequestContext.set(new RequestContext.Principal(
                rootId, "gl_root", true, company.getId(), branch.getId(), null));

        TrialBalanceDto tb = trialBalanceQuery.compute(company.getId());

        assertThat(tb.totalDebits())
                .isEqualByComparingTo(tb.totalCredits())
                .as("trial balance must net to zero after a sale posting");
    }

    // =========================================================================
    // Bar 3: void → SALES_REVERSAL; TB still nets to zero
    // =========================================================================

    @Test
    void void_producesReversalEntry_andTbStillNetsToZero() {
        SalesInvoiceDto invoice = createAndFinaliseInvoice();
        dispatcher.dispatchOne(getPendingFinalisedEvent(invoice.uid()).getId());

        // Now void the invoice
        RequestContext.set(new RequestContext.Principal(
                rootId, "gl_root", true, company.getId(), branch.getId(), null));
        salesInvoiceService.voidInvoice(invoice.uid(), new VoidInvoiceRequest("IT void test"));

        DomainEvent voidEvent = getPendingVoidedEvent(invoice.uid());
        dispatcher.dispatchOne(voidEvent.getId());

        // Two entries for this invoice uid: SALES and SALES_REVERSAL
        var entries = journalEntryRepo.findAll().stream()
                .filter(e -> invoice.uid().equals(e.getSourceRef()))
                .toList();
        assertThat(entries).hasSize(2);
        var types = entries.stream().map(e -> e.getSourceType().name()).toList();
        assertThat(types).containsExactlyInAnyOrder("SALES", "SALES_REVERSAL");

        // Restore context for TB query
        RequestContext.set(new RequestContext.Principal(
                rootId, "gl_root", true, company.getId(), branch.getId(), null));
        TrialBalanceDto tb = trialBalanceQuery.compute(company.getId());
        assertThat(tb.totalDebits())
                .isEqualByComparingTo(tb.totalCredits())
                .as("trial balance must net to zero after sale + reversal");
    }

    // =========================================================================
    // Bar 4: idempotency — double-dispatch produces one entry, not two
    // =========================================================================

    @Test
    void dispatchFinalisedTwice_idempotent_onlyOneJournalEntry() {
        SalesInvoiceDto invoice = createAndFinaliseInvoice();
        DomainEvent event = getPendingFinalisedEvent(invoice.uid());

        dispatcher.dispatchOne(event.getId());
        // Second dispatch — event is now DISPATCHED; dispatchOne should skip it
        dispatcher.dispatchOne(event.getId());

        long count = journalEntryRepo.findAll().stream()
                .filter(e -> invoice.uid().equals(e.getSourceRef()))
                .count();
        assertThat(count).isEqualTo(1)
                .as("idempotency: double-dispatch must not create two GL entries");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private SalesInvoiceDto createAndFinaliseInvoice() {
        SalesInvoiceDto draft = salesInvoiceService.create(
                new CreateSalesInvoiceRequest(
                        company.getUid(), customerUid, agentUid, "TZS", null, null));
        salesInvoiceService.addLine(draft.uid(), new AddInvoiceLineRequest(
                productUid, pcsUid, new BigDecimal("1"), null, null));
        // 1 × 1000 + 18% VAT = 1180
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

    private DomainEvent getPendingVoidedEvent(String invoiceUid) {
        return domainEventRepository.findAll().stream()
                .filter(e -> DomainEventType.SALE_VOIDED.equals(e.getEventType())
                          && invoiceUid.equals(e.getAggregateUid()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No SALE.VOIDED event found for invoice uid=" + invoiceUid));
    }
}
