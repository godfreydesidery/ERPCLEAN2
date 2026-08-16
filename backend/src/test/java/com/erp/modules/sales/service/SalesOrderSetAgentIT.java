package com.erp.modules.sales.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.gl.repository.GlConfigRepository;
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
import com.erp.modules.ap.service.ApGlSeeder;
import com.erp.modules.sales.domain.dto.AddSalesOrderLineRequest;
import com.erp.modules.sales.domain.dto.CancelSalesOrderRequest;
import com.erp.modules.sales.domain.dto.CreateDeliveryRequest;
import com.erp.modules.sales.domain.dto.CreateSalesOrderRequest;
import com.erp.modules.sales.domain.dto.DeliveryDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import com.erp.modules.sales.domain.dto.SalesOrderLineDto;
import com.erp.modules.sales.domain.enums.SalesOrderStatus;
import com.erp.modules.stock.domain.dto.StockReceivedPayload;
import com.erp.modules.stock.service.InventoryGlSeeder;
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
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Set/change-agent on a sales order so an agentless order becomes invoicable (bug fix).
 *
 * <p>Bars covered:
 * <ol>
 *   <li>An order created by a non-agent user (root) has no agent; invoice-from-delivery fails the
 *       agent check. After {@code setAgent}, the SO carries the agent and the invoice-from-delivery
 *       agent check passes (a DRAFT invoice is produced).</li>
 *   <li>A foreign/invalid agent uid is rejected.</li>
 *   <li>A cancelled order rejects setAgent with a friendly message (no internal BR-/ADR- token).</li>
 * </ol>
 */
class SalesOrderSetAgentIT extends PostgresIntegrationTest {

    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;

    @Autowired private ChartOfAccountService   chartOfAccountService;
    @Autowired private FiscalCalendarService   fiscalCalendarService;
    @Autowired private GlConfigService         glConfigService;
    @Autowired private GlConfigRepository      glConfigRepo;
    @Autowired private ApGlSeeder              apGlSeeder;
    @Autowired private InventoryGlSeeder       invGlSeeder;

    @Autowired private ProductService          productService;
    @Autowired private PriceListService        priceListService;
    @Autowired private UnitOfMeasureService    unitService;
    @Autowired private CustomerService         customerService;
    @Autowired private AgentService            agentService;
    @Autowired private TaxRateSeeder           taxRateSeeder;

    @Autowired private SalesOrderService       salesOrderService;
    @Autowired private DeliveryService         deliveryService;

    @Autowired private DomainEventRepository   domainEventRepo;
    @Autowired private DomainEventDispatcher   dispatcher;
    @Autowired private OutboxPublisher         outboxPublisher;
    @Autowired private TransactionTemplate     txTemplate;

    private Company  company;
    private Branch   branch;
    private Long     rootId;
    private String   pcsUid;
    private String   priceListUid;
    private String   customerUid;
    private String   agentUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("SetAgent IT Org"));
        company = companies.save(new Company(org, "SAIT", "SetAgent IT Co"));
        branch  = branches.save(new Branch(company, "SAIT1", "SetAgent IT Branch"));

        // Root user — deliberately NOT an internal agent, so created orders are agentless.
        AppUser root = new AppUser("sa_root", passwordEncoder.encode("S0Root!Xx"), "SA Root");
        root.setRoot(true);
        root   = users.save(inOrganisation(root, org.getId()));
        rootId = root.getId();

        setCtx();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        apGlSeeder.seedDefaults(company.getId());
        invGlSeeder.seedDefaults(company.getId());
        taxRateSeeder.seedDefaults(company.getId());

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        priceListUid = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();

        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "SA IT Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).uid();

        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "SA IT Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1 — agentless order is unbillable; setAgent fixes it → invoice succeeds
    // =========================================================================

    @Test
    void agentlessOrder_setAgent_makesInvoiceFromDeliverySucceed() {
        ProductDto product = stockableProduct("AgentlessWidget", "1000");
        publishAndDispatchReceipt(product, new BigDecimal("10"), new BigDecimal("500"));

        // Created by root (no internal agent) → agentless.
        SalesOrderDto so = createAgentlessOrder(product, new BigDecimal("3"));
        assertThat(so.agentId()).as("order created by a non-agent user has no agent").isNull();

        setCtx();
        salesOrderService.confirm(so.uid());

        // Deliver the full qty (delivery is created CONFIRMED in v1).
        SalesOrderLineDto soLine = salesOrderService.listLines(so.uid()).get(0);
        setCtx();
        DeliveryDto delivery = deliveryService.create(new CreateDeliveryRequest(
                so.uid(), LocalDate.now(), null,
                List.of(new CreateDeliveryRequest.DeliveryLineRequest(
                        soLine.uid(), new BigDecimal("3")))));

        // Before setAgent: invoice-from-delivery fails the agent check.
        setCtx();
        assertThatThrownBy(() -> deliveryService.createInvoiceFromDelivery(delivery.uid()))
                .hasMessageContaining("no agent");

        // Set the agent on the (now PARTIALLY/ FULLY fulfilled) order.
        setCtx();
        SalesOrderDto withAgent = salesOrderService.setAgent(so.uid(), agentUid);
        assertThat(withAgent.agentId()).as("setAgent assigns the agent").isNotNull();

        // After setAgent: invoice-from-delivery now passes the agent check.
        setCtx();
        SalesInvoiceDto invoice = deliveryService.createInvoiceFromDelivery(delivery.uid());
        assertThat(invoice).as("invoice is produced once an agent is assigned").isNotNull();
        assertThat(invoice.agentId()).isNotNull();
    }

    // =========================================================================
    // Bar 2 — foreign / invalid agent uid rejected
    // =========================================================================

    @Test
    void setAgent_unknownAgentUid_rejected() {
        ProductDto product = stockableProduct("RejectWidget", "1000");
        SalesOrderDto so = createAgentlessOrder(product, new BigDecimal("1"));

        setCtx();
        assertThatThrownBy(() -> salesOrderService.setAgent(so.uid(), "NOSUCHAGENT0000000000000000"))
                .hasMessageContaining("Agent not found");
    }

    // =========================================================================
    // Bar 3 — cancelled order rejects setAgent with a friendly (no-internal-token) message
    // =========================================================================

    @Test
    void setAgent_onCancelledOrder_rejectedWithFriendlyMessage() {
        ProductDto product = stockableProduct("CancelledWidget", "1000");
        SalesOrderDto so = createAgentlessOrder(product, new BigDecimal("1"));

        setCtx();
        salesOrderService.cancel(so.uid(), new CancelSalesOrderRequest("test"));

        setCtx();
        assertThatThrownBy(() -> salesOrderService.setAgent(so.uid(), agentUid))
                .satisfies(ex -> {
                    String msg = ex.getMessage();
                    assertThat(msg).contains("cancelled");
                    // No internal codes / identifiers leak to the user-facing message.
                    assertThat(msg).doesNotContain("BR-");
                    assertThat(msg).doesNotContain("ADR-");
                    assertThat(msg).doesNotContain(so.uid());
                });
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void setCtx() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "sa_root", true, company.getId(), branch.getId(), null));
    }

    private ProductDto stockableProduct(String name, String price) {
        setCtx();
        ProductDto p = productService.create(new CreateProductRequest(
                company.getUid(), null, name, null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));
        productService.setPrice(p.uid(),
                new SetProductPriceRequest(priceListUid, new MoneyDto(price, "TZS")));
        return p;
    }

    private SalesOrderDto createAgentlessOrder(ProductDto product, BigDecimal qty) {
        setCtx();
        // agentUid = null and the actor (root) has no internal agent → SO is agentless.
        SalesOrderDto so = salesOrderService.create(new CreateSalesOrderRequest(
                company.getUid(), customerUid, null, "TZS",
                LocalDate.now(), null, null, null, null));
        setCtx();
        salesOrderService.addLine(so.uid(), new AddSalesOrderLineRequest(
                product.uid(), pcsUid, qty, null, null, null));
        return so;
    }

    private void publishAndDispatchReceipt(ProductDto product, BigDecimal qty, BigDecimal cost) {
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
        dispatcher.dispatchOne(pendingEvent(DomainEventType.STOCK_RECEIVED));
    }

    private Long pendingEvent(String eventType) {
        return domainEventRepo.findAll().stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .filter(e -> DomainEventStatus.PENDING == e.getStatus())
                .reduce((a, b) -> b)
                .map(DomainEvent::getId)
                .orElseThrow(() -> new AssertionError("No PENDING event of type: " + eventType));
    }
}
