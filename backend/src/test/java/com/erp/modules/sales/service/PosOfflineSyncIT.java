package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.cashbank.domain.dto.CreateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.service.CashBankAccountService;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
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
import com.erp.modules.sales.domain.dto.AcknowledgeOfflineReviewRequest;
import com.erp.modules.sales.domain.dto.CreatePosTillRequest;
import com.erp.modules.sales.domain.dto.OfflineSyncRequest;
import com.erp.modules.sales.domain.dto.OfflineSyncResultDto;
import com.erp.modules.sales.domain.dto.OpenSessionRequest;
import com.erp.modules.sales.domain.dto.PosOfflineReviewDto;
import com.erp.modules.sales.domain.dto.PosTillDto;
import com.erp.modules.sales.domain.dto.UpdateSalesSettingsRequest;
import com.erp.modules.sales.domain.entity.PosSession;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.enums.BelowCostAction;
import com.erp.modules.sales.domain.enums.DocumentOrigin;
import com.erp.modules.sales.domain.enums.InvoiceStatus;
import com.erp.modules.sales.domain.enums.OfflinePeriodReason;
import com.erp.modules.sales.domain.enums.OfflineReviewKind;
import com.erp.modules.sales.domain.enums.OfflineReviewStatus;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.repository.PosOfflinePeriodRepository;
import com.erp.modules.sales.repository.PosOfflineReviewRepository;
import com.erp.modules.sales.repository.PosSessionRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.modules.stock.domain.dto.StockReceivedPayload;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Offline POS sync (ADR-0068 D-4, D-5, D-8) against a real Postgres with V114: offline sales post
 * at the time they were SOLD and the price PAID, are never refused for stock, are idempotent, and
 * what they broke lands on the review list; sales offline selling never permits are HELD, not lost.
 */
class PosOfflineSyncIT extends PostgresIntegrationTest {

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private UserBranchRepository userBranches;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private ChartOfAccountRepository chartOfAccountRepo;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private GlConfigService glConfigService;
    @Autowired private TaxRateSeeder taxRateSeeder;
    @Autowired private CashBankAccountService cashBankAccountService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private PriceListService priceListService;
    @Autowired private ProductService productService;
    @Autowired private AgentService agentService;
    @Autowired private CustomerService customerService;
    @Autowired private PosTillService tillService;
    @Autowired private PosSessionService sessionService;
    @Autowired private PosOfflineSyncService offlineService;
    @Autowired private SalesSettingsService salesSettingsService;
    @Autowired private SalesInvoiceRepository invoiceRepo;
    @Autowired private PosSessionRepository sessionRepo;
    @Autowired private PosOfflineReviewRepository reviewRepo;
    @Autowired private PosOfflinePeriodRepository periodRepo;
    @Autowired private DomainEventRepository domainEventRepo;
    @Autowired private DomainEventDispatcher dispatcher;
    @Autowired private OutboxPublisher outboxPublisher;
    @Autowired private TransactionTemplate txTemplate;

    private Company company;
    private Branch branch;
    private Long rootId;
    private Long cashierId;
    private Long customerId;
    private String pcsUid;
    private String priceListUid;
    private PosTillDto till;
    private String sessionUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("Offline IT Org"));
        company = companies.save(new Company(org, "OFFL", "Offline Co"));
        branch = branches.save(new Branch(company, "OF1", "Offline Branch"));

        AppUser root = new AppUser("offline_root", passwordEncoder.encode("R00t!Pass1"), "Offline Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();
        setRootCtx();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        taxRateSeeder.seedDefaults(company.getId());
        String cashGlUid = chartOfAccountRepo.findByCompanyIdAndAccountCode(company.getId(), "1000")
                .or(() -> chartOfAccountRepo.findByCompanyIdAndAccountCode(company.getId(), "1100"))
                .map(a -> a.getUid()).orElseThrow();
        cashBankAccountService.create(new CreateCashBankAccountRequest(
                company.getUid(), null, "POS Cash", CashBankAccountType.CASH,
                null, null, null, cashGlUid, true));
        pcsUid = unitService.create(new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        priceListUid = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();

        AppUser cashier = new AppUser("offline_cashier", passwordEncoder.encode("C@sh1Pass"), "Offline Cashier");
        cashier.setOrganisationId(org.getId());
        cashierId = users.save(cashier).getId();
        userBranches.save(new UserBranch(cashierId, branch, rootId));
        agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "Cashier Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.INTERNAL, cashierId));
        customerId = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Walk-In",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).id();
        // Block negative stock: offline sales must still post (and be flagged) under BLOCK.
        salesSettingsService.update(new UpdateSalesSettingsRequest(
                company.getUid(), false, null, "TZS", false));

        till = tillService.createTill(new CreatePosTillRequest(company.getUid(), branch.getId(), "Till 1", null));
        till = tillService.setOfflineAllowed(till.uid(), true);
        setCashierCtx();
        sessionUid = sessionService.openSession(new OpenSessionRequest(till.uid(), BigDecimal.ZERO)).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void offlineSale_postsAtTheTimeSold_andARepeatSyncDoesNotPostItTwice() {
        ProductDto bread = stocked("Bread", "1000", "20");
        Instant soldAt = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        var sale = sale("OFF-T1-000001", soldAt, bread, "1", "1000");

        OfflineSyncResultDto first = offlineService.sync(batch(List.of(), List.of(sale)));
        assertThat(first.sales()).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo("POSTED");
            assertThat(r.flags()).isEmpty();
            assertThat(r.invoiceNumber()).isNotBlank();
        });

        SalesInvoice inv = invoiceRepo.findByUid(first.sales().get(0).invoiceUid()).orElseThrow();
        assertThat(inv.getStatus()).isEqualTo(InvoiceStatus.FINALISED);
        assertThat(inv.getOrigin()).isEqualTo(DocumentOrigin.POS);
        assertThat(inv.isSoldOffline()).isTrue();
        assertThat(inv.getOfflineNumber()).isEqualTo("OFF-T1-000001");
        assertThat(inv.getFinalisedAt())
                .as("the business time is when it was sold, not when it synced")
                .isEqualTo(soldAt);
        assertThat(inv.getSyncedAt()).isAfter(soldAt);
        assertThat(inv.getPosSessionId()).isEqualTo(sessionRepo.findByUid(sessionUid).orElseThrow().getId());

        OfflineSyncResultDto again = offlineService.sync(batch(List.of(), List.of(sale)));
        assertThat(again.sales()).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo("ALREADY_POSTED");
            assertThat(r.invoiceUid()).isEqualTo(first.sales().get(0).invoiceUid());
        });
        assertThat(invoiceRepo.findAll()).hasSize(1);
    }

    @Test
    void pricePaidStands_andTheDifferenceIsFlagged() {
        ProductDto milk = stocked("Milk", "1000", "20");
        var sale = sale("OFF-T1-000002", Instant.now().minusSeconds(600), milk, "2", "900");

        var r = offlineService.sync(batch(List.of(), List.of(sale))).sales().get(0);

        assertThat(r.status()).isEqualTo("POSTED");
        assertThat(r.flags()).containsExactly(OfflineReviewKind.PRICE_DIFFERENCE.name());
        SalesInvoice inv = invoiceRepo.findByUid(r.invoiceUid()).orElseThrow();
        assertThat(inv.getNetTotalAmount())
                .as("charged at the till's price, 2 x 900")
                .isEqualByComparingTo("1800");
        assertThat(reviewRepo.findBySalesInvoiceId(inv.getId())).singleElement()
                .satisfies(x -> assertThat(x.getDetail()).contains("Milk").contains("900").contains("1000"));
    }

    @Test
    void oversoldOfflineSale_postsUnderBlock_andIsFlagged() {
        ProductDto soda = stocked("Soda", "500", "1");
        var sale = sale("OFF-T1-000003", Instant.now().minusSeconds(300), soda, "3", "500");

        var r = offlineService.sync(batch(List.of(), List.of(sale))).sales().get(0);

        assertThat(r.status())
                .as("the goods already left: a company that blocks negative stock still gets the sale")
                .isEqualTo("POSTED");
        assertThat(r.flags()).containsExactly(OfflineReviewKind.NEGATIVE_STOCK.name());
    }

    @Test
    void belowCostOfflineSale_postsUnderBlock_andIsFlagged() {
        setRootCtx();
        salesSettingsService.update(new UpdateSalesSettingsRequest(
                company.getUid(), false, null, "TZS", false, BelowCostAction.BLOCK));
        setCashierCtx();
        // Received at a cost of 100 a unit; the till sold it offline at 80.
        ProductDto salt = stocked("Salt", "500", "10");
        var sale = sale("OFF-T1-000008", Instant.now().minusSeconds(120), salt, "1", "80");

        var r = offlineService.sync(batch(List.of(), List.of(sale))).sales().get(0);

        assertThat(r.status())
                .as("the money is already taken: BLOCK cannot refuse an offline sale")
                .isEqualTo("POSTED");
        assertThat(r.flags()).contains(OfflineReviewKind.BELOW_COST.name(),
                OfflineReviewKind.PRICE_DIFFERENCE.name());
        assertThat(reviewRepo.findBySalesInvoiceId(
                        invoiceRepo.findByUid(r.invoiceUid()).orElseThrow().getId()))
                .filteredOn(x -> x.getKind() == OfflineReviewKind.BELOW_COST)
                .singleElement()
                .satisfies(x -> assertThat(x.getDetail()).contains("Salt").contains("80").contains("100"));
    }

    @Test
    void belowCostOfflineSale_isNotFlagged_whenThePolicyIsOff() {
        ProductDto pepper = stocked("Pepper", "500", "10");
        var sale = sale("OFF-T1-000009", Instant.now().minusSeconds(120), pepper, "1", "80");

        var r = offlineService.sync(batch(List.of(), List.of(sale))).sales().get(0);

        assertThat(r.flags())
                .as("OFF means no below-cost check, online and offline alike")
                .doesNotContain(OfflineReviewKind.BELOW_COST.name());
    }

    @Test
    void tillNotAllowedOffline_isHeldWithTheSaleAsRung_andNotListedTwice() {
        setRootCtx();
        tillService.setOfflineAllowed(till.uid(), false);
        setCashierCtx();
        ProductDto bun = stocked("Bun", "300", "10");
        var sale = sale("OFF-T1-000004", Instant.now().minusSeconds(60), bun, "1", "300");

        var r = offlineService.sync(batch(List.of(), List.of(sale))).sales().get(0);
        offlineService.sync(batch(List.of(), List.of(sale)));

        assertThat(r.status()).isEqualTo("HELD");
        assertThat(r.message()).contains("not allowed to sell offline");
        assertThat(invoiceRepo.findAll()).isEmpty();
        assertThat(reviewRepo.findAll()).singleElement().satisfies(x -> {
            assertThat(x.getKind()).isEqualTo(OfflineReviewKind.HELD);
            assertThat(x.getHeldRequest()).contains("OFF-T1-000004");
        });
    }

    @Test
    void complimentaryAndUnreferencedMobileMoney_areHeld() {
        ProductDto tea = stocked("Tea", "800", "10");
        var comp = new OfflineSyncRequest.OfflineSale(UUID.randomUUID().toString(), "OFF-T1-000005",
                Instant.now().minusSeconds(60), sessionUid, null, customerId, "TZS",
                List.of(line(tea, "1", "800")),
                List.of(new OfflineSyncRequest.Tender(TenderType.COMPLIMENTARY, new BigDecimal("944"), "Guest")),
                new BigDecimal("944"), null, null);
        var mobile = new OfflineSyncRequest.OfflineSale(UUID.randomUUID().toString(), "OFF-T1-000006",
                Instant.now().minusSeconds(60), sessionUid, null, customerId, "TZS",
                List.of(line(tea, "1", "800")),
                List.of(new OfflineSyncRequest.Tender(TenderType.MOBILE_MONEY, new BigDecimal("944"), " ")),
                new BigDecimal("944"), null, null);

        var results = offlineService.sync(batch(List.of(), List.of(comp, mobile))).sales();

        assertThat(results).extracting(OfflineSyncResultDto.SaleResult::status).containsExactly("HELD", "HELD");
        assertThat(results.get(0).message()).contains("cash, mobile money or card");
        assertThat(results.get(1).message()).contains("reference");
        assertThat(invoiceRepo.findAll()).isEmpty();
    }

    @Test
    void shiftOpenedOffline_isCreatedOnce_withItsOwnOpeningTime_andHoldsItsSales() {
        setRootCtx();
        PosTillDto till2 = tillService.createTill(new CreatePosTillRequest(company.getUid(), branch.getId(), "Till 2", null));
        tillService.setOfflineAllowed(till2.uid(), true);
        setCashierCtx();
        ProductDto egg = stocked("Egg", "400", "30");
        Instant opened = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        var shift = new OfflineSyncRequest.OfflineSession("shift-key-1", opened, new BigDecimal("5000"));
        var sale = new OfflineSyncRequest.OfflineSale(UUID.randomUUID().toString(), "OFF-T2-000001",
                Instant.now().minus(2, ChronoUnit.HOURS), null, "shift-key-1", customerId, "TZS",
                List.of(line(egg, "2", "400")),
                List.of(new OfflineSyncRequest.Tender(TenderType.CASH, new BigDecimal("5000"), null)),
                new BigDecimal("944"), null, null);
        var req = new OfflineSyncRequest(till2.uid(), List.of(shift), List.of(sale), List.of());

        var result = offlineService.sync(req);
        offlineService.sync(req);

        assertThat(result.sessions()).singleElement().satisfies(s -> assertThat(s.clientKey()).isEqualTo("shift-key-1"));
        PosSession created = sessionRepo.findByUid(result.sessions().get(0).sessionUid()).orElseThrow();
        assertThat(created.isOpenedOffline()).isTrue();
        assertThat(created.getOpenedAt()).isEqualTo(opened);
        assertThat(sessionRepo.findAll().stream().filter(PosSession::isOpenedOffline)).hasSize(1);
        assertThat(result.sales()).singleElement().satisfies(r -> assertThat(r.status()).isEqualTo("POSTED"));
        assertThat(invoiceRepo.findByUid(result.sales().get(0).invoiceUid()).orElseThrow().getPosSessionId())
                .isEqualTo(created.getId());
    }

    @Test
    void periodsAreRecordedOnce_andReviewItemsCanBeAcknowledgedOnce() {
        ProductDto jam = stocked("Jam", "1000", "5");
        var priced = sale("OFF-T1-000007", Instant.now().minusSeconds(120), jam, "1", "950");
        var period = new OfflineSyncRequest.OfflinePeriod("period-1", OfflinePeriodReason.UNREACHABLE,
                null, Instant.now().minusSeconds(3600), null, 1, sessionUid, null);
        var req = new OfflineSyncRequest(till.uid(), List.of(), List.of(priced), List.of(period));

        assertThat(offlineService.sync(req).periodsRecorded()).isEqualTo(1);
        var closed = new OfflineSyncRequest.OfflinePeriod("period-1", OfflinePeriodReason.UNREACHABLE,
                null, period.startedAt(), Instant.now(), 3, sessionUid, null);
        offlineService.sync(new OfflineSyncRequest(till.uid(), List.of(), List.of(), List.of(closed)));

        assertThat(periodRepo.findAll()).singleElement().satisfies(p -> {
            assertThat(p.getEndedAt()).isNotNull();
            assertThat(p.getSalesCount()).isEqualTo(3);
            assertThat(p.getSwitchedBy()).isEqualTo(cashierId);
        });

        setRootCtx();
        List<PosOfflineReviewDto> open = offlineService.listReviews(OfflineReviewStatus.OPEN, 0, 20).getContent();
        assertThat(open).singleElement().satisfies(x -> {
            assertThat(x.kind()).isEqualTo(OfflineReviewKind.PRICE_DIFFERENCE);
            assertThat(x.tillName()).isEqualTo("Till 1");
            assertThat(x.invoiceNumber()).isNotBlank();
        });
        var acked = offlineService.acknowledge(open.get(0).uid(), new AcknowledgeOfflineReviewRequest("checked"));
        assertThat(acked.status()).isEqualTo(OfflineReviewStatus.ACKNOWLEDGED);
        assertThat(acked.acknowledgedByName()).isEqualTo("Offline Root");
        assertThatThrownBy(() -> offlineService.acknowledge(open.get(0).uid(), null))
                .isInstanceOf(ConflictException.class);
        assertThat(offlineService.listReviews(OfflineReviewStatus.OPEN, 0, 20).getContent()).isEmpty();
        assertThat(offlineService.listPeriods(0, 20).getContent()).singleElement()
                .satisfies(p -> assertThat(p.switchedByName()).isEqualTo("Offline Cashier"));
    }

    // ------------------------------------------------------------------------- helpers

    private OfflineSyncRequest batch(List<OfflineSyncRequest.OfflineSession> shifts,
                                     List<OfflineSyncRequest.OfflineSale> sales) {
        return new OfflineSyncRequest(till.uid(), shifts, sales, List.of());
    }

    /** A cash sale of {@code qty} at {@code price}, over-tendered so it is always fully paid. */
    private OfflineSyncRequest.OfflineSale sale(String number, Instant soldAt, ProductDto p,
                                                String qty, String price) {
        return new OfflineSyncRequest.OfflineSale(UUID.randomUUID().toString(), number, soldAt,
                sessionUid, null, customerId, "TZS", List.of(line(p, qty, price)),
                List.of(new OfflineSyncRequest.Tender(TenderType.CASH, new BigDecimal("100000"), null)),
                new BigDecimal(price).multiply(new BigDecimal(qty)), null, null);
    }

    private OfflineSyncRequest.Line line(ProductDto p, String qty, String price) {
        return new OfflineSyncRequest.Line(p.id(), unitService.getByUid(pcsUid).id(),
                new BigDecimal(qty), new BigDecimal(price), false);
    }

    /** A priced, stocked GOODS product. */
    private ProductDto stocked(String name, String price, String onHand) {
        setRootCtx();
        ProductDto dto = productService.create(new CreateProductRequest(
                company.getUid(), null, name, null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));
        productService.setPrice(dto.uid(), new SetProductPriceRequest(priceListUid, new MoneyDto(price, "TZS")));
        ProductDto product = productService.getByUid(dto.uid());
        StockReceivedPayload payload = new StockReceivedPayload(
                product.uid(), company.getId(), branch.getId(), Instant.now(),
                List.of(new StockReceivedPayload.LineItem(product.id(), product.uid(), null,
                        new BigDecimal(onHand), new BigDecimal("100"))));
        txTemplate.execute(s -> {
            outboxPublisher.publish(DomainEventType.STOCK_RECEIVED, DomainEventType.AGG_GOODS_RECEIPT,
                    product.id(), product.uid(), company.getId(), branch.getId(), payload);
            return null;
        });
        Long eventId = domainEventRepo.findAll().stream()
                .filter(e -> DomainEventType.STOCK_RECEIVED.equals(e.getEventType()))
                .filter(e -> product.uid().equals(e.getAggregateUid()))
                .filter(e -> DomainEventStatus.PENDING == e.getStatus())
                .map(DomainEvent::getId).reduce((a, b) -> b).orElseThrow();
        dispatcher.dispatchOne(eventId);
        setCashierCtx();
        return product;
    }

    private void setRootCtx() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "offline_root", true, company.getId(), branch.getId(), null));
    }

    private void setCashierCtx() {
        RequestContext.set(new RequestContext.Principal(
                cashierId, "offline_cashier", false, company.getId(), branch.getId(), null));
    }
}
