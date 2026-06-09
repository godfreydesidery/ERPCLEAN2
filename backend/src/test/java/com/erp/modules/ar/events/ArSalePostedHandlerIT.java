package com.erp.modules.ar.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ar.domain.enums.ArInvoiceSource;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
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
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link ArSalePostedHandler} (ADR-0014 D-5, FR-AR-01/02, BR-AR-01/02).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>Credit-account sale → SALE.FINALISED dispatched → ar_invoice created OPEN, outstanding =
 *       receivable (gross total).
 *   <li>No GL double-post: the GL AR-control account (1200) is debited exactly ONCE — by
 *       SalesPostingHandler. ArSalePostedHandler must NOT post to GL (D-5, BR-AR-02).
 *   <li>Cash walk-in sale → SALE.FINALISED dispatched → NO ar_invoice created (FR-AR-02,
 *       BR-AR-01).
 *   <li>Idempotency: dispatching the same SALE.FINALISED event twice creates exactly one
 *       ar_invoice (ADR-0009 D-6).
 * </ol>
 */
class ArSalePostedHandlerIT extends PostgresIntegrationTest {

    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private TaxRateSeeder taxRateSeeder;
    @Autowired private CustomerService customerService;
    @Autowired private AgentService agentService;
    @Autowired private ProductService productService;
    @Autowired private PriceListService priceListService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private DomainEventDispatcher dispatcher;
    @Autowired private ArInvoiceRepository arInvoices;
    @Autowired private JournalEntryRepository journalEntryRepo;
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

    // seeded party / product refs
    private String creditCustomerUid;  // CREDIT_ACCOUNT
    private String cashCustomerUid;    // CASH_WALK_IN
    private String agentUid;
    private String pcsUid;
    private String priceListUid;
    private String productUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("AR Handler IT Org"));
        company = companies.save(new Company(org, "ARIT", "AR Handler IT Co"));
        branch  = branches.save(new Branch(company, "ARIT1", "AR Handler IT Branch"));

        AppUser root = new AppUser("ar_root", passwordEncoder.encode("RootPass1!"), "AR Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "ar_root", true, company.getId(), branch.getId(), null));

        taxRateSeeder.seedDefaults(company.getId());

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        priceListUid = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();
        productUid = productService.create(new CreateProductRequest(
                company.getUid(), null, "AR Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD)).uid();
        productService.setPrice(productUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "TZS")));

        // Credit-account customer (creates AR open item)
        creditCustomerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Credit Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null)).uid();

        // Cash walk-in customer (no AR open item)
        cashCustomerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Cash Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null)).uid();

        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "AR Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();

        // GL prerequisites
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: Credit-account sale → ar_invoice OPEN with outstanding == gross
    // =========================================================================

    @Test
    void creditSale_finalised_createsOpenArInvoice() {
        // Credit sale: no payment tendered — service should accept when isCashSale=false
        // Actually SalesInvoiceService requires payment-before-finalise for ALL invoices.
        // For a credit-account customer the GL posts DR AR / CR Revenue.
        // We still need to finalise via the service: add gross as a single CREDIT tender.
        SalesInvoiceDto invoice = finaliseInvoiceFor(creditCustomerUid);

        DomainEvent event = getSaleFinalisedEvent(invoice.uid());
        dispatcher.dispatchOne(event.getId());

        var openItems = arInvoices.findAll().stream()
                .filter(i -> invoice.uid().equals(i.getSourceInvoiceUid()))
                .toList();
        assertThat(openItems).hasSize(1);

        var item = openItems.get(0);
        assertThat(item.getStatus()).isEqualTo(ArInvoiceStatus.OPEN);
        assertThat(item.getSource()).isEqualTo(ArInvoiceSource.SALE);
        assertThat(item.getOutstandingAmount())
                .as("outstanding must equal the invoice gross total (the receivable)")
                .isEqualByComparingTo(invoice.grossTotalAmount());
        assertThat(item.getOriginalAmount())
                .isEqualByComparingTo(invoice.grossTotalAmount());
    }

    // =========================================================================
    // Bar 2: No GL double-post — GL AR-control debited exactly ONCE
    // =========================================================================

    @Test
    void creditSale_finalised_glArControlDebitedExactlyOnce_noDoublePost() {
        SalesInvoiceDto invoice = finaliseInvoiceFor(creditCustomerUid);

        // Dispatch SALE.FINALISED — SalesPostingHandler AND ArSalePostedHandler both run
        DomainEvent event = getSaleFinalisedEvent(invoice.uid());
        dispatcher.dispatchOne(event.getId());

        // Exactly ONE GL journal entry for this invoice's source_ref (from SalesPostingHandler)
        var entries = journalEntryRepo.findAll().stream()
                .filter(e -> invoice.uid().equals(e.getSourceRef()))
                .toList();
        assertThat(entries)
                .as("ArSalePostedHandler must NOT post to GL — only SalesPostingHandler does (BR-AR-02, D-5)")
                .hasSize(1);
    }

    // =========================================================================
    // Bar 3: Cash walk-in sale → no ar_invoice
    // =========================================================================

    @Test
    void cashSale_finalised_noArInvoiceCreated() {
        SalesInvoiceDto invoice = finaliseInvoiceFor(cashCustomerUid);

        dispatcher.dispatchOne(getSaleFinalisedEvent(invoice.uid()).getId());

        long count = arInvoices.findAll().stream()
                .filter(i -> invoice.uid().equals(i.getSourceInvoiceUid()))
                .count();
        assertThat(count)
                .as("Cash walk-in sale must not create an AR open item (FR-AR-02, BR-AR-01)")
                .isZero();
    }

    // =========================================================================
    // Bar 4: Idempotency — double dispatch → one ar_invoice
    // =========================================================================

    @Test
    void creditSale_doubleDispatch_idempotent_oneArInvoice() {
        SalesInvoiceDto invoice = finaliseInvoiceFor(creditCustomerUid);
        DomainEvent event = getSaleFinalisedEvent(invoice.uid());

        dispatcher.dispatchOne(event.getId());
        // Second dispatch — event is already DISPATCHED; dispatcher skips it
        dispatcher.dispatchOne(event.getId());

        long count = arInvoices.findAll().stream()
                .filter(i -> invoice.uid().equals(i.getSourceInvoiceUid()))
                .count();
        assertThat(count)
                .as("double-dispatch must not create two ar_invoices (ADR-0009 D-6)")
                .isEqualTo(1);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Creates, lines, and finalises a sales invoice for the given customer.
     * For CREDIT_ACCOUNT customers: no payment required; outstanding = gross = 1180 TZS.
     * For CASH_WALK_IN customers: full payment tendered (1180 TZS).
     * Price 1000 TZS STANDARD → gross = 1180 TZS.
     */
    private SalesInvoiceDto finaliseInvoiceFor(String customerUid) {
        SalesInvoiceDto draft = salesInvoiceService.create(
                new CreateSalesInvoiceRequest(company.getUid(), customerUid, agentUid, "TZS", null, null));
        salesInvoiceService.addLine(draft.uid(),
                new AddInvoiceLineRequest(productUid, pcsUid, new BigDecimal("1"), null, null));
        // Credit-account customers don't need paid-in-full (ADR-0014 D-10); cash customers do.
        boolean isCash = cashCustomerUid.equals(customerUid);
        if (isCash) {
            salesInvoiceService.addPayment(draft.uid(),
                    new AddPaymentRequest(TenderType.CASH, new BigDecimal("1180"), "TZS", null));
        }
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        return salesInvoiceService.getByUid(draft.uid());
    }

    private DomainEvent getSaleFinalisedEvent(String invoiceUid) {
        return domainEventRepository.findAll().stream()
                .filter(e -> DomainEventType.SALE_FINALISED.equals(e.getEventType())
                          && invoiceUid.equals(e.getAggregateUid()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No SALE.FINALISED event found for invoice uid=" + invoiceUid));
    }
}
