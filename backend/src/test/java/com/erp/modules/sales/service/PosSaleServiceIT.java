package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.cashbank.domain.dto.CreateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.service.CashBankAccountService;
import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
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
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.PriceListService;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.sales.domain.dto.CreatePosTillRequest;
import com.erp.modules.sales.domain.dto.OpenSessionRequest;
import com.erp.modules.sales.domain.dto.PosSaleRequest;
import com.erp.modules.sales.domain.dto.PosSessionDto;
import com.erp.modules.sales.domain.dto.PosTillDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.UpdateSalesSettingsRequest;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.enums.DocumentOrigin;
import com.erp.modules.sales.domain.enums.InvoiceStatus;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import com.erp.modules.stock.domain.dto.StockReceivedPayload;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventStatus;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Basic end-to-end integration test for the core POS sale path
 * ({@link PosSaleService#processSale}) against a real Postgres.
 *
 * <p><b>Why this exists:</b> until ADR-0044 Wave 1, NO integration test rang a real POS sale through
 * to a persisted {@code origin='POS'} invoice — which is exactly why the
 * {@code chk_sales_invoice_origin_refs} gap (a POS insert violating the V17 constraint, fixed in V72)
 * went unnoticed. This IT is the permanent guard for that class of bug: it proves a plain POS sale
 * finalises and persists with {@code origin=POS}, and that an idempotent replay returns the original
 * invoice without creating a second.
 *
 * <p>Uses the singleton Testcontainers container (Ryuk disabled per the Windows convention,
 * {@link PostgresIntegrationTest}).
 */
class PosSaleServiceIT extends PostgresIntegrationTest {

    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private UserBranchRepository    userBranches;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;

    @Autowired private ChartOfAccountService   chartOfAccountService;
    @Autowired private ChartOfAccountRepository chartOfAccountRepo;
    @Autowired private FiscalCalendarService   fiscalCalendarService;
    @Autowired private GlConfigService         glConfigService;
    @Autowired private TaxRateSeeder           taxRateSeeder;

    @Autowired private CashBankAccountService  cashBankAccountService;
    @Autowired private UnitOfMeasureService    unitService;
    @Autowired private PriceListService        priceListService;
    @Autowired private ProductService          productService;
    @Autowired private AgentService            agentService;
    @Autowired private CustomerService         customerService;
    @Autowired private PosTillService          tillService;
    @Autowired private PosSessionService       sessionService;
    @Autowired private PosSaleService          saleService;
    @Autowired private SalesSettingsService    salesSettingsService;
    @Autowired private SalesSettingsRepository salesSettingsRepo;
    @Autowired private SalesInvoiceRepository  invoiceRepo;
    @Autowired private StockOnHandRepository   stockOnHandRepo;
    @Autowired private JournalEntryRepository  journalEntryRepo;
    @Autowired private DomainEventRepository   domainEventRepo;
    @Autowired private DomainEventDispatcher   dispatcher;
    @Autowired private OutboxPublisher         outboxPublisher;
    @Autowired private TransactionTemplate     txTemplate;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private Long    cashierId;
    private Long    customerId;
    private String  pcsUid;
    private String  priceListUid;
    private String  sessionUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("POS Sale IT Org"));
        company = companies.save(new Company(org, "POSSALE", "POS Sale Co"));
        branch  = branches.save(new Branch(company, "PS1", "POS Sale Branch"));

        AppUser root = new AppUser("possale_root", passwordEncoder.encode("R00t!Pass1"), "POS Sale Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root   = users.save(root);
        rootId = root.getId();

        setRootCtx();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        taxRateSeeder.seedDefaults(company.getId());

        String cashGlUid = chartOfAccountRepo
                .findByCompanyIdAndAccountCode(company.getId(), "1000")
                .map(a -> a.getUid())
                .orElseGet(() ->
                        chartOfAccountRepo
                                .findByCompanyIdAndAccountCode(company.getId(), "1100")
                                .map(a -> a.getUid())
                                .orElseThrow(() -> new IllegalStateException(
                                        "No cash GL account seeded for the test company")));
        cashBankAccountService.create(new CreateCashBankAccountRequest(
                company.getUid(), null, "POS Cash", CashBankAccountType.CASH,
                null, null, null, cashGlUid, true));

        pcsUid       = unitService.create(new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        priceListUid = priceListService.create(new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();

        AppUser cashier = new AppUser("possale_cashier", passwordEncoder.encode("C@sh1Pass"), "POS Cashier");
        cashier.setOrganisationId(org.getId());
        cashier = users.save(cashier);
        cashierId = cashier.getId();
        userBranches.save(new UserBranch(cashierId, branch, rootId));

        agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "POS Cashier Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.INTERNAL, cashierId));

        customerId = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Walk-In Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).id();

        PosTillDto till = tillService.createTill(new CreatePosTillRequest(
                company.getUid(), branch.getId(), "Till 1", null));
        setCashierCtx();
        PosSessionDto session = sessionService.openSession(
                new OpenSessionRequest(till.uid(), BigDecimal.ZERO));
        sessionUid = session.uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Core guard: a plain POS cash sale finalises and persists origin=POS.
    // (This is the regression guard for the V17/V37/V72 chk_sales_invoice_origin_refs gap.)
    // =========================================================================

    @Test
    void processSale_cashSale_finalisesAndPersistsPosOrigin() {
        setRootCtx();
        ProductDto product = productService.getByUid(pricedProduct("Bread", "1000"));
        // Real stock behind the sale: NegativeStockGuard blocks a POS sale that would take on-hand
        // negative, and this test is about the persisted origin, not about overselling.
        receiveStock(product, new BigDecimal("20"), new BigDecimal("500"));
        Long productId = product.id();

        setCashierCtx();
        SalesInvoiceDto result = saleService.processSale(null, cashSaleRequest(productId));

        assertThat(result.status())
                .as("POS sale must finalise")
                .isEqualTo(InvoiceStatus.FINALISED);
        assertThat(result.grossTotalAmount()).isGreaterThan(BigDecimal.ZERO);

        SalesInvoice persisted = invoiceRepo.findByUid(result.uid()).orElseThrow();
        assertThat(persisted.getOrigin())
                .as("POS sale must persist origin=POS — guards chk_sales_invoice_origin_refs (V72)")
                .isEqualTo(DocumentOrigin.POS);
        assertThat(persisted.getPosSessionId())
                .as("POS sale must be tagged to its session")
                .isNotNull();
    }

    // =========================================================================
    // Idempotency replay: same key returns the original invoice, no second sale.
    // =========================================================================

    @Test
    void processSale_idempotencyReplay_returnsOriginalAndDoesNotDoublePost() {
        setRootCtx();
        ProductDto product = productService.getByUid(pricedProduct("Sugar 1kg", "2000"));
        // Stocked, so the replay path is exercised rather than the negative-stock block.
        receiveStock(product, new BigDecimal("20"), new BigDecimal("500"));
        Long productId = product.id();

        setCashierCtx();
        PosSaleRequest req = cashSaleRequest(productId);
        String key = "pos-sale-it-key-001";

        SalesInvoiceDto first  = saleService.processSale(key, req);
        SalesInvoiceDto replay = saleService.processSale(key, req);

        assertThat(replay.uid())
                .as("a replay with the same Idempotency-Key returns the original invoice")
                .isEqualTo(first.uid());

        long posInvoices = invoiceRepo.findAll().stream()
                .filter(i -> i.getOrigin() == DocumentOrigin.POS)
                .count();
        assertThat(posInvoices)
                .as("a replay must not create a second POS invoice")
                .isEqualTo(1L);
    }

    // =========================================================================
    // FIX 2 (busy-day sim, CRITICAL): POS reverse/void on an OPEN session must succeed
    // (previously always 409 via the AR-oriented settled-tender guard) — invoice VOIDED,
    // SALE.VOIDED emitted, and stock/GL reversed once dispatched.
    // =========================================================================

    @Test
    void reverseSale_posCashSale_openSession_voidsInvoiceAndReversesStockAndGl_noConflict() {
        setRootCtx();
        ProductDto product = productService.create(new CreateProductRequest(
                company.getUid(), null, "Reversible Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));
        productService.setPrice(product.uid(),
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "TZS")));
        receiveStock(product, new BigDecimal("50"), new BigDecimal("500"));
        BigDecimal qtyBeforeSale = requireSoh(product.id()).getQuantity();

        setCashierCtx();
        SalesInvoiceDto sale = saleService.processSale(null, cashSaleRequest(product.id()));
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED, sale.uid()));

        // Sanity: the sale actually moved stock and posted GL before we reverse it.
        assertThat(requireSoh(product.id()).getQuantity())
                .as("stock must be issued by the original sale before we test the reversal")
                .isLessThan(qtyBeforeSale);
        JournalEntry salesEntry = journalEntryRepo
                .findByCompanyIdAndSourceTypeAndSourceRef(company.getId(), JournalSourceType.SALES, sale.uid())
                .orElseThrow(() -> new AssertionError("No SALES journal entry posted for the sale"));

        // Act — this must NOT throw (the busy-day-sim defect: always 409 via
        // FLOW-ORDER-TO-CASH-027's settled-tender guard, making POS void unsatisfiable).
        //
        // Reversed under the ROOT context, not the cashier's. A till reversal now needs supervisor
        // authority — either the caller's own SALES.INVOICE.VOID or a re-verified manager step-up
        // (K1 C3) — and this IT's cashier is a bare AppUser with no role at all, so it has neither.
        // Root satisfies the authority rule, which leaves this test asserting the thing it was
        // written to assert: that the POS void path itself reverses the invoice, the stock and the
        // GL without tripping the AR settled-tender guard. Who may authorise a reversal is covered
        // exhaustively by PosSaleServiceImplTest and StepUpAuthServiceImplTest.
        setRootCtx();
        saleService.reverseSale(sale.uid(), "cashier rang the wrong item");

        SalesInvoice voided = invoiceRepo.findByUid(sale.uid()).orElseThrow();
        assertThat(voided.getStatus())
                .as("the POS invoice must be VOIDED, not left FINALISED behind a 409")
                .isEqualTo(InvoiceStatus.VOID);

        DomainEvent voidedEvent = domainEventRepo.findAll().stream()
                .filter(e -> DomainEventType.SALE_VOIDED.equals(e.getEventType()))
                .filter(e -> sale.uid().equals(e.getAggregateUid()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SALE.VOIDED event emitted for the reversal"));
        assertThat(voidedEvent.getStatus()).isEqualTo(DomainEventStatus.PENDING);

        dispatcher.dispatchOne(voidedEvent.getId());

        assertThat(requireSoh(product.id()).getQuantity())
                .as("reversing the sale must restore stock to its pre-sale level")
                .isEqualByComparingTo(qtyBeforeSale);
        assertThat(journalEntryRepo.existsByReversalOfId(salesEntry.getId()))
                .as("a SALES_REVERSAL journal entry must offset the original SALES posting")
                .isTrue();
    }

    // =========================================================================
    // Negative-stock block on the POS path. The till is where the client's overselling actually
    // happened, and until now NO test drove NegativeStockGuard through PosSaleService — the DIRECT
    // invoice cases in DeliveryServiceIT were the only coverage. Both settings shapes are covered:
    // an explicitly saved allow_negative_stock=false, and the far more common shape in the field,
    // a company that has NO sales_settings row at all.
    // =========================================================================

    @Test
    void processSale_exceedsOnHand_blocked_whenAllowNegativeStockSavedAsFalse() {
        setRootCtx();
        salesSettingsService.update(new UpdateSalesSettingsRequest(
                company.getUid(), false, null, "TZS", false));

        ProductDto product = productService.getByUid(pricedProduct("Cooking Oil 1L", "3000"));
        receiveStock(product, new BigDecimal("2"), new BigDecimal("1500"));

        setCashierCtx();
        PosSaleRequest oversell = cashSaleRequest(product.id(), new BigDecimal("5"));
        assertThatThrownBy(() -> saleService.processSale(null, oversell))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Not enough stock of Cooking Oil 1L")
                .hasMessageContaining("2 available")
                .hasMessageContaining("5 requested");

        assertThat(requireSoh(product.id()).getQuantity())
                .as("a blocked POS sale must leave on-hand untouched")
                .isEqualByComparingTo(new BigDecimal("2"));
        assertThat(invoiceRepo.findAll())
                .as("a blocked POS sale must not leave an invoice behind")
                .isEmpty();
    }

    @Test
    void processSale_exceedsOnHand_blocked_whenNoSalesSettingsRowAtAll() {
        // This company is created straight through the repository, so it has NO sales_settings row —
        // exactly the state a company that never opened the Sales Settings screen is in. The guard
        // must read that as BLOCK, matching what the screen reports for the same missing row.
        setRootCtx();
        assertThat(salesSettingsRepo.findByCompanyId(company.getId()))
                .as("fixture precondition: no sales_settings row for this company")
                .isEmpty();

        ProductDto product = productService.getByUid(pricedProduct("Maize Flour 2kg", "4000"));
        receiveStock(product, new BigDecimal("3"), new BigDecimal("2000"));

        setCashierCtx();
        PosSaleRequest oversell = cashSaleRequest(product.id(), new BigDecimal("4"));
        assertThatThrownBy(() -> saleService.processSale(null, oversell))
                .as("an un-configured company must not be able to oversell from the till")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Not enough stock of Maize Flour 2kg");

        assertThat(requireSoh(product.id()).getQuantity())
                .as("a blocked POS sale must leave on-hand untouched")
                .isEqualByComparingTo(new BigDecimal("3"));
    }

    // ------------------------------------------------------------------------- helpers

    /** Publishes+dispatches a STOCK.RECEIVED event so the product has on-hand before a sale. */
    private void receiveStock(ProductDto product, BigDecimal qty, BigDecimal cost) {
        StockReceivedPayload payload = new StockReceivedPayload(
                product.uid(), company.getId(), branch.getId(), Instant.now(),
                List.of(new StockReceivedPayload.LineItem(
                        product.id(), product.uid(), null, qty, cost)));
        txTemplate.execute(s -> {
            outboxPublisher.publish(DomainEventType.STOCK_RECEIVED,
                    DomainEventType.AGG_GOODS_RECEIPT, product.id(), product.uid(),
                    company.getId(), branch.getId(), payload);
            return null;
        });
        dispatcher.dispatchOne(pendingEvent(DomainEventType.STOCK_RECEIVED, product.uid()));
    }

    private Long pendingEvent(String eventType, String aggregateUid) {
        return domainEventRepo.findAll().stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .filter(e -> aggregateUid.equals(e.getAggregateUid()))
                .filter(e -> DomainEventStatus.PENDING == e.getStatus())
                .reduce((a, b) -> b)
                .map(DomainEvent::getId)
                .orElseThrow(() -> new AssertionError("No PENDING event of type: " + eventType));
    }

    private StockOnHand requireSoh(Long productId) {
        return stockOnHandRepo
                .findByCompanyIdAndBranchIdAndProductId(company.getId(), branch.getId(), productId)
                .orElseThrow(() -> new AssertionError("No on-hand row for productId=" + productId));
    }

    /** Creates a non-restricted GOODS product (restrictedKind defaults to NONE) with a retail price. */
    private String pricedProduct(String name, String price) {
        ProductDto dto = productService.create(new CreateProductRequest(
                company.getUid(), null, name, null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));
        productService.setPrice(dto.uid(), new SetProductPriceRequest(priceListUid, new MoneyDto(price, "TZS")));
        return dto.uid();
    }

    /** One-line exact-cash sale (no tenders => server settles exact gross in CASH; no ageVerified). */
    private PosSaleRequest cashSaleRequest(Long productId) {
        return cashSaleRequest(productId, BigDecimal.ONE);
    }

    /** As above, for a specific quantity (used by the negative-stock cases). */
    private PosSaleRequest cashSaleRequest(Long productId, BigDecimal qty) {
        return new PosSaleRequest(
                sessionUid, customerId, null, "TZS",
                List.of(new PosSaleRequest.LineItem(productId, unitId(), qty, null, null)),
                null, BigDecimal.TEN, null, null);
    }

    private Long unitId() {
        return unitService.getByUid(pcsUid).id();
    }

    private void setRootCtx() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "possale_root", true, company.getId(), branch.getId(), null));
    }

    private void setCashierCtx() {
        RequestContext.set(new RequestContext.Principal(
                cashierId, "possale_cashier", false, company.getId(), branch.getId(), null));
    }
}
