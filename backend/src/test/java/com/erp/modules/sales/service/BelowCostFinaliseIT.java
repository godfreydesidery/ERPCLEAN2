package com.erp.modules.sales.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.erp.modules.products.domain.dto.ProductDto;
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
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.enums.BelowCostAction;
import com.erp.modules.sales.domain.enums.InvoiceStatus;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import com.erp.modules.stock.domain.dto.StockReceivedPayload;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditRepository;
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
 * Integration coverage for the V93 "sale at or below cost" policy <b>at its call site</b> —
 * {@link SalesInvoiceServiceImpl#finalise}, which is also the POS path
 * ({@link PosSaleServiceImpl#processSale} delegates to it).
 *
 * <p><b>Why a call-site IT, when {@code BelowCostGuardTest} already unit-tests the guard.</b>
 * The guard's own suite constructs {@code BelowCostGuard} directly and feeds it hand-built
 * {@code PricedLine}s. Nothing in it would notice if the single call in {@code finalise} were
 * deleted, or moved ABOVE {@code totalsCalc.recompute}. Both mistakes leave every existing test
 * green while the feature silently stops working — which is exactly how the negative-stock policy
 * shipped broken to a live client. These tests drive the REAL service against a REAL Postgres and
 * a REAL moving-average cost (established the way production establishes it: a costed
 * {@code STOCK.RECEIVED} event, not a hand-written {@code avg_cost}).
 *
 * <p><b>The headline test</b>
 * ({@link #finalise_vatInclusiveLineBelowCostAfterDocumentDiscount_blocked}) is built so that all
 * three failure modes break it independently:
 * <ul>
 *   <li><b>Wiring</b> — delete the {@code belowCostGuard.assertNotBelowCost(...)} call and the
 *       finalise succeeds.</li>
 *   <li><b>Ordering</b> — move the call ABOVE {@code totalsCalc.recompute} and it reads the
 *       DRAFT-time line net (1,186, comfortably above the 1,000 cost, asserted explicitly below),
 *       because the document discount is only apportioned by the finalise recompute. The sale then
 *       goes through.</li>
 *   <li><b>The VAT trap (ADR-0056)</b> — compare the GROSS (the raw {@code unit_price_amount} of
 *       1,400, or the post-discount gross of 1,150) instead of the derived NET and nothing is ever
 *       below the 1,000 cost, so the sale goes through.</li>
 * </ul>
 *
 * <p>Fixture idiom follows {@link SalesInvoiceServiceImplIT} (company/branch/customer/product/price/
 * unit seeding, CREDIT_ACCOUNT customer so a finalise needs no tender) and {@link PosSaleServiceIT}
 * (costed {@code STOCK.RECEIVED} publish + deterministic {@code dispatchOne}). A separate class
 * rather than extra methods on {@code SalesInvoiceServiceImplIT} because that suite's {@code setUp}
 * deliberately seeds NO stock and opts the company into backorder — the exact opposite of what a
 * cost-based policy needs.
 */
class BelowCostFinaliseIT extends PostgresIntegrationTest {

    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;

    @Autowired private ChartOfAccountService   chartOfAccountService;
    @Autowired private FiscalCalendarService   fiscalCalendarService;
    @Autowired private GlConfigService         glConfigService;
    @Autowired private TaxRateSeeder           taxRateSeeder;

    @Autowired private UnitOfMeasureService    unitService;
    @Autowired private PriceListService        priceListService;
    @Autowired private ProductService          productService;
    @Autowired private CustomerService         customerService;
    @Autowired private AgentService            agentService;

    @Autowired private SalesInvoiceService     salesInvoiceService;
    @Autowired private SalesSettingsService    salesSettingsService;
    @Autowired private SalesSettingsRepository salesSettingsRepo;
    @Autowired private SalesInvoiceRepository  invoiceRepo;
    @Autowired private StockOnHandRepository   stockOnHandRepo;
    @Autowired private AuditRepository         auditRepo;

    @Autowired private DomainEventRepository   domainEventRepo;
    @Autowired private DomainEventDispatcher   dispatcher;
    @Autowired private OutboxPublisher         outboxPublisher;
    @Autowired private TransactionTemplate     txTemplate;

    /** Moving-average cost every costed product in this suite is received at. */
    private static final String UNIT_COST = "1000";

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private Long    clerkId;

    private String pcsUid;
    /** VAT-INCLUSIVE list (ADR-0056): unit_price_amount holds a GROSS amount. */
    private String inclusiveListUid;
    /** Classic VAT-exclusive list: unit_price_amount holds a NET amount. */
    private String exclusiveListUid;
    private String customerUid;
    private String agentUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Below Cost IT Org"));
        company = companies.save(new Company(org, "BCOST", "Below Cost Co"));
        branch  = branches.save(new Branch(company, "BC1", "Below Cost Branch"));

        AppUser root = new AppUser("bcost_root", passwordEncoder.encode("R00t!Pass1"), "Below Cost Root");
        root.setRoot(true);
        root   = users.save(inOrganisation(root, org.getId()));
        rootId = root.getId();

        // Non-root clerk in the SAME company: passes ScopeGuard, holds no granted permissions, so
        // SALES.BELOW_COST.OVERRIDE is denied for them (the APPROVE "flag alone is not enough" bar).
        AppUser clerk = users.save(inOrganisation(
                new AppUser("bcost_clerk", passwordEncoder.encode("Cl3rk!Pass1"), "Below Cost Clerk"),
                org.getId()));
        clerkId = clerk.getId();

        setRootCtx();

        // GL seeding mirrors PosSaleServiceIT: the STOCK.RECEIVED handler posts DR Inventory /
        // CR GRNI for a costed receipt. Not seeding it only produces a soft anomaly WARN, but the
        // receipt is the thing establishing our cost basis, so keep that path fully configured.
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        // Companies created straight through the repository skip BootstrapRunner's tax seeding.
        taxRateSeeder.seedDefaults(company.getId());

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        inclusiveListUid = priceListService.create(new CreatePriceListRequest(
                company.getUid(), "RETAIL_INC", "Retail (VAT inclusive)",
                null, null, null, true, null, null)).uid();
        exclusiveListUid = priceListService.create(new CreatePriceListRequest(
                company.getUid(), "TRADE_EXC", "Trade (VAT exclusive)",
                null, null, null, false, null, null)).uid();

        // CREDIT_ACCOUNT with no credit limit → finalises without a tender, so these tests exercise
        // the below-cost policy and nothing else (idiom borrowed from SalesInvoiceServiceImplIT).
        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Below Cost Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).uid();
        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "Below Cost Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();

        // NB: NO sales_settings row is written here — noSettingsRow_belowCostLine_finalises depends
        // on that, and every other test sets the policy it needs explicitly.
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // 1. THE HEADLINE TEST — VAT-inclusive + document discount, action BLOCK.
    //    Covers wiring, ordering vs totalsCalc.recompute, and the ADR-0056 VAT trap at once.
    // =========================================================================

    @Test
    void finalise_vatInclusiveLineBelowCostAfterDocumentDiscount_blocked() {
        setPolicy(BelowCostAction.BLOCK, false);

        // Cost basis: 10 received at 1,000 → moving-average cost = 1,000.
        ProductDto widget = costedProduct("VAT Trap Widget", inclusiveListUid, "1400");
        assertThat(requireAvgCost(widget))
                .as("fixture precondition: a real moving-average cost of 1,000 from the receipt")
                .isEqualByComparingTo(new BigDecimal("1000"));

        String invoiceUid = draftWithLine(widget, "1");

        // DRAFT state: gross 1,400 (inclusive), net = round(1400 / 1.18) = 1,186 — ABOVE the 1,000
        // cost. A guard that ran before the finalise recompute would read exactly this number and
        // wave the sale through.
        assertThat(salesInvoiceService.listLines(invoiceUid).get(0).netAmount())
                .as("draft-time line net must sit ABOVE cost, so only the finalise recompute can "
                        + "reveal the loss — this is what makes the guard's ordering load-bearing")
                .isEqualByComparingTo(new BigDecimal("1186"));

        // A 250 document discount, applied the only way it can be today (no service method exposes
        // it). It is apportioned onto the line by InvoiceTotalsCalculator.recompute at FINALISE:
        //   raw gross 1,400 − 250 = 1,150  →  net = round(1150 / 1.18) = 975  ≤  cost 1,000.
        // Note both the list gross (1,400) and the discounted gross (1,150) stay ABOVE cost: only
        // the NET, after VAT is stripped and the discount is apportioned, is a loss.
        applyDocumentDiscount(invoiceUid, "250");

        assertThatThrownBy(() -> salesInvoiceService.finalise(invoiceUid, new FinaliseInvoiceRequest()))
                .as("BLOCK must reject a line whose NET unit price is at or below moving-average cost")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("VAT Trap Widget")
                .hasMessageContaining("cannot be completed");

        SalesInvoiceDto after = salesInvoiceService.getByUid(invoiceUid);
        assertThat(after.status())
                .as("a blocked finalise must roll back — nothing numbered, nothing on the outbox")
                .isEqualTo(InvoiceStatus.DRAFT);
        assertThat(after.invoiceNumber()).isNull();
        assertThat(pendingSaleFinalisedEvents(invoiceUid))
                .as("a blocked sale must never reach the outbox")
                .isZero();
    }

    // =========================================================================
    // 2. The same shape, priced comfortably above cost — the guard must not reject everything.
    // =========================================================================

    @Test
    void finalise_vatInclusiveLineAboveCost_succeeds() {
        setPolicy(BelowCostAction.BLOCK, false);

        // Gross 2,000 inclusive → net = round(2000 / 1.18) = 1,695 > cost 1,000.
        ProductDto widget = costedProduct("Healthy Margin Widget", inclusiveListUid, "2000");
        String invoiceUid = draftWithLine(widget, "1");

        salesInvoiceService.finalise(invoiceUid, new FinaliseInvoiceRequest());

        SalesInvoiceDto after = salesInvoiceService.getByUid(invoiceUid);
        assertThat(after.status())
                .as("BLOCK must not stand in the way of a profitable sale")
                .isEqualTo(InvoiceStatus.FINALISED);
        assertThat(after.invoiceNumber()).isNotBlank();
    }

    // =========================================================================
    // 3. OFF changes nothing (the pre-V93 behaviour, and the shipped default).
    // =========================================================================

    @Test
    void finalise_actionOff_belowCostLine_finalisesUnchanged() {
        setPolicy(BelowCostAction.OFF, false);

        // 500 net against a 1,000 cost — a plain loss under any other action.
        ProductDto widget = costedProduct("Loss Leader", exclusiveListUid, "500");
        String invoiceUid = draftWithLine(widget, "1");

        salesInvoiceService.finalise(invoiceUid, new FinaliseInvoiceRequest());

        assertThat(salesInvoiceService.getByUid(invoiceUid).status())
                .as("OFF is 'no check at all' — a below-cost sale must finalise exactly as before V93")
                .isEqualTo(InvoiceStatus.FINALISED);
    }

    // =========================================================================
    // 4. No sales_settings row at all → OFF. The cross-layer contract, pinned end-to-end.
    //    (Its unit-level sibling is BelowCostSettingCrossLayerContractTest; this is the same
    //    agreement asserted against a real row-less company and the real finalise path.)
    // =========================================================================

    @Test
    void finalise_noSalesSettingsRow_belowCostLine_finalises() {
        // Deliberately NO setPolicy(...) call: this company has never opened the Sales Settings
        // screen, which is the commonest state in the field.
        assertThat(salesSettingsRepo.findByCompanyId(company.getId()))
                .as("fixture precondition: no sales_settings row for this company")
                .isEmpty();

        ProductDto widget = costedProduct("Unconfigured Widget", exclusiveListUid, "500");
        String invoiceUid = draftWithLine(widget, "1");

        salesInvoiceService.finalise(invoiceUid, new FinaliseInvoiceRequest());

        assertThat(salesInvoiceService.getByUid(invoiceUid).status())
                .as("a missing settings row must resolve to OFF — the same answer the Sales "
                        + "Settings API gives for that state")
                .isEqualTo(InvoiceStatus.FINALISED);
    }

    // =========================================================================
    // 5. APPROVE end to end: the flag AND the permission are both required.
    // =========================================================================

    @Test
    void finalise_actionApprove_requiresBothTheFlagAndThePermission() {
        setPolicy(BelowCostAction.APPROVE, false);

        ProductDto widget = costedProduct("Supervisor Widget", exclusiveListUid, "500");
        String invoiceUid = draftWithLine(widget, "1");

        // (a) No approval flag — rejected even though root holds every permission.
        setRootCtx();
        assertThatThrownBy(() -> salesInvoiceService.finalise(invoiceUid, new FinaliseInvoiceRequest()))
                .as("APPROVE without the request flag must be rejected")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("supervisor needs to approve");
        assertThat(salesInvoiceService.getByUid(invoiceUid).status()).isEqualTo(InvoiceStatus.DRAFT);

        // (b) Flag set, but by a caller WITHOUT SALES.BELOW_COST.OVERRIDE — still rejected.
        //     A flag any till client could set must never be sufficient on its own.
        setClerkCtx();
        assertThatThrownBy(() ->
                salesInvoiceService.finalise(invoiceUid, new FinaliseInvoiceRequest(null, true)))
                .as("the approval flag alone must authorise nothing — the permission is required too")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("supervisor needs to approve");
        setRootCtx();
        assertThat(salesInvoiceService.getByUid(invoiceUid).status()).isEqualTo(InvoiceStatus.DRAFT);

        // (c) Flag set by a caller who DOES hold SALES.BELOW_COST.OVERRIDE — accepted and audited.
        //     Root holds it via the PermissionResolver root short-circuit; that is the shipped
        //     semantic and the same fixture SalesOrderCreditBlockIT uses for SALES.CREDIT.OVERRIDE.
        //     Granting the permission to the non-root clerk instead would need a role + grant +
        //     user_company membership fixture that no sales IT currently carries.
        setRootCtx();
        salesInvoiceService.finalise(invoiceUid, new FinaliseInvoiceRequest(null, true));

        assertThat(salesInvoiceService.getByUid(invoiceUid).status())
                .as("flag + permission together must let the supervisor-approved sale through")
                .isEqualTo(InvoiceStatus.FINALISED);
        assertThat(hasAudit(AuditActions.SALES_BELOW_COST_OVERRIDE, invoiceUid))
                .as("an approved below-cost sale must leave a SALES.BELOW_COST.OVERRIDE audit row")
                .isTrue();
    }

    // =========================================================================
    // 6. Unknown cost never blocks (fail-open, owner decision) — recorded, not silent.
    // =========================================================================

    @Test
    void finalise_actionBlock_productWithNoEstablishedCost_finalises() {
        // allow_negative_stock=true so the NEGATIVE-STOCK policy cannot be what lets this test pass
        // or fail: this product is deliberately never received, so it has no on-hand row and no cost.
        setPolicy(BelowCostAction.BLOCK, true);

        ProductDto widget = uncostedProduct("Never Received Widget", exclusiveListUid, "500");
        assertThat(stockOnHandRepo.findAllByCompanyIdAndBranchIdAndProductId(
                        company.getId(), branch.getId(), widget.id()))
                .as("fixture precondition: no on-hand row, therefore no established cost")
                .isEmpty();

        String invoiceUid = draftWithLine(widget, "1");
        salesInvoiceService.finalise(invoiceUid, new FinaliseInvoiceRequest());

        assertThat(salesInvoiceService.getByUid(invoiceUid).status())
                .as("BLOCK must never jam a sale over a costing data gap (owner decision)")
                .isEqualTo(InvoiceStatus.FINALISED);
        assertThat(hasAudit(AuditActions.SALES_BELOW_COST_WARNING, invoiceUid))
                .as("an unchecked line must still be RECORDED — fail-open, not silent")
                .isTrue();
    }

    @Test
    void finalise_actionBlock_productWithZeroAverageCost_finalises() {
        // The second fail-open surface: an avg_cost of 0 means "not costed yet", never "free".
        // Read literally, a `<=` against zero would reject every line for a company that has never
        // costed its stock. Stock IS on hand here, so the negative-stock guard is satisfied.
        setPolicy(BelowCostAction.BLOCK, false);

        ProductDto widget = productWithPrice("Zero Cost Widget", exclusiveListUid, "500");
        receiveStock(widget, "10", "0");
        assertThat(requireAvgCost(widget))
                .as("fixture precondition: a costed on-hand row whose avg_cost is zero")
                .isEqualByComparingTo(BigDecimal.ZERO);

        String invoiceUid = draftWithLine(widget, "1");
        salesInvoiceService.finalise(invoiceUid, new FinaliseInvoiceRequest());

        assertThat(salesInvoiceService.getByUid(invoiceUid).status())
                .as("a zero avg_cost is 'uncosted', so BLOCK must let the sale through")
                .isEqualTo(InvoiceStatus.FINALISED);
    }

    // ------------------------------------------------------------------------- helpers

    /** Writes the company's below-cost policy (and negative-stock stance) via the real service. */
    private void setPolicy(BelowCostAction action, boolean allowNegativeStock) {
        setRootCtx();
        salesSettingsService.update(new UpdateSalesSettingsRequest(
                company.getUid(), false, null, "TZS", allowNegativeStock, action));
    }

    /** A sellable, stockable STANDARD-VAT product priced on the given list — no stock, no cost. */
    private ProductDto uncostedProduct(String name, String priceListUid, String price) {
        return productWithPrice(name, priceListUid, price);
    }

    /** As above, then receives 10 units at {@link #UNIT_COST} so the moving average is established. */
    private ProductDto costedProduct(String name, String priceListUid, String price) {
        ProductDto product = productWithPrice(name, priceListUid, price);
        receiveStock(product, "10", UNIT_COST);
        return product;
    }

    private ProductDto productWithPrice(String name, String priceListUid, String price) {
        setRootCtx();
        ProductDto product = productService.create(new CreateProductRequest(
                company.getUid(), null, name, null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));
        productService.setPrice(product.uid(),
                new SetProductPriceRequest(priceListUid, new MoneyDto(price, "TZS")));
        return product;
    }

    /**
     * Establishes a REAL moving-average cost the way production does: publish a costed
     * {@code STOCK.RECEIVED} event and dispatch it, so {@code InventoryValuationService} recomputes
     * {@code stock_on_hand.avg_cost} — never a hand-written avg_cost. (Idiom: PosSaleServiceIT.)
     */
    private void receiveStock(ProductDto product, String qty, String unitCost) {
        StockReceivedPayload payload = new StockReceivedPayload(
                product.uid(), company.getId(), branch.getId(), Instant.now(),
                List.of(new StockReceivedPayload.LineItem(
                        product.id(), product.uid(), null,
                        new BigDecimal(qty), new BigDecimal(unitCost))));
        txTemplate.execute(s -> {
            outboxPublisher.publish(DomainEventType.STOCK_RECEIVED,
                    DomainEventType.AGG_GOODS_RECEIPT, product.id(), product.uid(),
                    company.getId(), branch.getId(), payload);
            return null;
        });
        dispatcher.dispatchOne(pendingEvent(DomainEventType.STOCK_RECEIVED, product.uid()));
    }

    /** The moving-average cost the guard will actually read, via the same (company, branch) scope. */
    private BigDecimal requireAvgCost(ProductDto product) {
        return stockOnHandRepo
                .findAllByCompanyIdAndBranchIdAndProductId(
                        company.getId(), branch.getId(), product.id())
                .stream()
                .map(soh -> soh.getAvgCost())
                .filter(c -> c != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No established avg_cost for product " + product.name()));
    }

    /** Creates a DRAFT invoice for the credit customer with one line of the product. */
    private String draftWithLine(ProductDto product, String qty) {
        setRootCtx();
        SalesInvoiceDto draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                company.getUid(), customerUid, agentUid, "TZS", null, null));
        salesInvoiceService.addLine(draft.uid(), new AddInvoiceLineRequest(
                product.uid(), pcsUid, new BigDecimal(qty), null, null));
        return draft.uid();
    }

    /**
     * Stamps a document discount on the DRAFT header. No service method exposes one today
     * (SalesInvoiceServiceImplIT records the same gap), so the test writes the column directly —
     * production code is untouched. This is what makes the header's discount reach the LINE nets
     * only at finalise, via InvoiceTotalsCalculator.recompute.
     */
    private void applyDocumentDiscount(String invoiceUid, String amount) {
        setRootCtx();
        SalesInvoice inv = invoiceRepo.findByUid(invoiceUid).orElseThrow();
        inv.setDocDiscountAmount(new BigDecimal(amount));
        invoiceRepo.save(inv);
    }

    private long pendingSaleFinalisedEvents(String invoiceUid) {
        return domainEventRepo.findAll().stream()
                .filter(e -> DomainEventType.SALE_FINALISED.equals(e.getEventType()))
                .filter(e -> invoiceUid.equals(e.getAggregateUid()))
                .count();
    }

    private boolean hasAudit(String action, String targetUid) {
        return auditRepo.findAll().stream()
                .anyMatch(a -> action.equals(a.getAction()) && targetUid.equals(a.getTargetUid()));
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

    private void setRootCtx() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "bcost_root", true, company.getId(), branch.getId(), null));
    }

    private void setClerkCtx() {
        RequestContext.set(new RequestContext.Principal(
                clerkId, "bcost_clerk", false, company.getId(), branch.getId(), null));
    }
}
