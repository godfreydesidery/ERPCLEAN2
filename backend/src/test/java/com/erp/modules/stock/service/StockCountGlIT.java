package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ap.service.ApGlSeeder;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.GlConfigRepository;
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
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.stock.domain.dto.AdjustStockRequest;
import com.erp.modules.stock.domain.dto.CreateStockCountRequest;
import com.erp.modules.stock.domain.dto.EnterCountRequest;
import com.erp.modules.stock.domain.dto.StockCountDto;
import com.erp.modules.stock.domain.dto.StockCountLineDto;
import com.erp.modules.stock.domain.dto.StockReceivedPayload;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.domain.enums.AdjustmentReason;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
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
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stock count → GL, end to end against the real schema.
 *
 * <ul>
 *   <li><b>LBO-03</b>: a posted count moves the Inventory GL by exactly the count's variance value
 *       — once. Before the fix every line also posted its own journal through
 *       {@code revalueAdjustment}, on top of the count's net journal, so the variance hit the GL
 *       twice and the Inventory GL stopped agreeing with Σ on_hand_value.</li>
 *   <li><b>STK-02</b>: the variance is measured against the system quantity at the moment the
 *       line was counted, so trading between counting and posting is not reversed by the post.</li>
 * </ul>
 */
class StockCountGlIT extends PostgresIntegrationTest {

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    @Autowired private ChartOfAccountService  chartOfAccountService;
    @Autowired private FiscalCalendarService  fiscalCalendarService;
    @Autowired private GlConfigService        glConfigService;
    @Autowired private GlConfigRepository     glConfigRepo;
    @Autowired private JournalLineRepository  journalLines;
    @Autowired private ApGlSeeder             apGlSeeder;
    @Autowired private InventoryGlSeeder      invGlSeeder;

    @Autowired private ProductService         productService;
    @Autowired private UnitOfMeasureService   unitService;

    @Autowired private StockCountService      stockCountService;
    @Autowired private StockService           stockService;
    @Autowired private StockOnHandRepository  stockOnHandRepo;
    @Autowired private StockLocationRepository stockLocationRepo;
    @Autowired private LocationResolver       locationResolver;

    @Autowired private DomainEventRepository  domainEventRepo;
    @Autowired private DomainEventDispatcher  dispatcher;
    @Autowired private OutboxPublisher        outboxPublisher;
    @Autowired private TransactionTemplate    txTemplate;
    @Autowired private JdbcTemplate           jdbc;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  pcsUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("StockCount IT Org"));
        company = companies.save(new Company(org, "SCGL", "StockCount IT Co"));
        branch  = branches.save(new Branch(company, "SCGL1", "StockCount IT Branch"));

        AppUser root = new AppUser("scgl_root", passwordEncoder.encode("ScGl@1!Xxyy"), "SC Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();

        setCtx();
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        apGlSeeder.seedDefaults(company.getId());
        invGlSeeder.seedDefaults(company.getId());

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // LBO-03
    // =========================================================================

    @Test
    void postedCount_movesInventoryGlByTheVarianceValue_once() {
        ProductDto a = stockableProduct("Count-A");
        ProductDto b = stockableProduct("Count-B");
        receive("RCPT-SC-A", a, "10", "500", 1L);   // 5,000
        receive("RCPT-SC-B", b, "20", "100", 2L);   // 2,000

        BigDecimal inventoryBefore = inventoryBalance();
        BigDecimal adjustBefore    = stockAdjBalance();
        assertThat(inventoryBefore).isEqualByComparingTo("7000");

        setCtx();
        StockCountDto count = stockCountService.create(new CreateStockCountRequest(
                defaultLocationUid(), LocalDate.now(), "FULL", null, null));
        stockCountService.enterCount(count.uid(), new EnterCountRequest(List.of(
                new EnterCountRequest.LineEntry(lineFor(count, a).id(), new BigDecimal("8"), null),
                new EnterCountRequest.LineEntry(lineFor(count, b).id(), new BigDecimal("23"), null))));

        StockCountDto posted = stockCountService.post(count.uid(), LocalDate.now());

        // Variance: A −2 × 500 = −1,000; B +3 × 100 = +300 → net −700.
        BigDecimal varianceValue = posted.lines().stream()
                .map(StockCountLineDto::varianceValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(varianceValue).isEqualByComparingTo("-700");

        // Trial balance: Inventory moved by exactly the variance value, and the adjustment
        // account by its mirror — once, not twice.
        assertThat(inventoryBalance().subtract(inventoryBefore))
                .as("Inventory GL movement must equal the count's variance value (LBO-03)")
                .isEqualByComparingTo(varianceValue);
        assertThat(stockAdjBalance().subtract(adjustBefore))
                .as("Stock adjustment account must carry the variance once")
                .isEqualByComparingTo(varianceValue.negate());

        // Exactly one stock-adjustment journal, sourced on the count.
        Integer journals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE company_id = ? "
                        + "AND source_type = 'STOCK_ADJUSTMENT'",
                Integer.class, company.getId());
        assertThat(journals).isEqualTo(1);
        assertThat(posted.varianceGlEntryUid()).isNotNull();

        // And the recon invariant: Σ on_hand_value == Inventory GL.
        BigDecimal sumValue = stockOnHandRepo.findByCompanyIdAndProductId(company.getId(), a.id())
                .stream().map(StockOnHand::getOnHandValue).reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(stockOnHandRepo.findByCompanyIdAndProductId(company.getId(), b.id())
                        .stream().map(StockOnHand::getOnHandValue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertThat(sumValue).isEqualByComparingTo(inventoryBalance());

        // The owner's historic-repair query (docs/ops) must parse against the real schema and
        // find nothing to repair for a count posted by the fixed code.
        for (String sql : repairQueries()) {
            assertThat(jdbc.queryForList(sql)).as("repair query: %s", sql).isEmpty();
        }
    }

    private static List<String> repairQueries() {
        try {
            String text = java.nio.file.Files.readString(
                    java.nio.file.Path.of("..", "docs", "ops", "lbo-03-double-posted-stock-counts.sql"));
            String withoutComments = text.lines()
                    .filter(l -> !l.trim().startsWith("--"))
                    .collect(java.util.stream.Collectors.joining("\n"));
            return java.util.Arrays.stream(withoutComments.split(";"))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void setCtx() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "scgl_root", true, company.getId(), branch.getId(), null));
    }

    private ProductDto stockableProduct(String name) {
        setCtx();
        return productService.create(new CreateProductRequest(
                company.getUid(), null, name, null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));
    }

    private void receive(String receiptUid, ProductDto product, String qty, String cost, Long aggId) {
        StockReceivedPayload payload = new StockReceivedPayload(
                receiptUid, company.getId(), branch.getId(), Instant.now(),
                List.of(new StockReceivedPayload.LineItem(
                        product.id(), product.uid(), null, new BigDecimal(qty), new BigDecimal(cost))));
        txTemplate.execute(s -> {
            outboxPublisher.publish(DomainEventType.STOCK_RECEIVED,
                    DomainEventType.AGG_GOODS_RECEIPT, aggId, receiptUid,
                    company.getId(), branch.getId(), payload);
            return null;
        });
        Long eventId = domainEventRepo.findAll().stream()
                .filter(e -> DomainEventType.STOCK_RECEIVED.equals(e.getEventType()))
                .filter(e -> DomainEventStatus.PENDING == e.getStatus())
                .reduce((x, y) -> y)
                .map(DomainEvent::getId)
                .orElseThrow();
        dispatcher.dispatchOne(eventId);
    }

    private String defaultLocationUid() {
        Long locId = txTemplate.execute(s ->
                locationResolver.defaultLocationId(company.getId(), branch.getId()));
        return stockLocationRepo.findById(locId).orElseThrow().getUid();
    }

    private static StockCountLineDto lineFor(StockCountDto count, ProductDto product) {
        return count.lines().stream()
                .filter(l -> product.id().equals(l.productId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No count line for " + product.name()));
    }

    private BigDecimal balanceOf(GlConfigKey key) {
        BigDecimal b = journalLines.accountBalance(company.getId(), glConfigRepo
                .findByCompanyIdAndConfigKey(company.getId(), key).orElseThrow().getAccountId());
        return b != null ? b : BigDecimal.ZERO;
    }

    private BigDecimal inventoryBalance() {
        return balanceOf(GlConfigKey.INVENTORY);
    }

    private BigDecimal stockAdjBalance() {
        return balanceOf(GlConfigKey.STOCK_ADJUSTMENT);
    }
}
