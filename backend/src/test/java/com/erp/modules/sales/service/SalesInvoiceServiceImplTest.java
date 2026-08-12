package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.products.domain.dto.UnitListPriceDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteResult;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBulkPack;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.UnitPriceStatus;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.PriceResolutionService;
import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceLineDto;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.entity.TaxRate;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link SalesInvoiceServiceImpl#addLine} — ADR-0048 D-1 (multi-unit / per-pack
 * pricing). {@code addLine} now delegates to {@code PriceResolutionService.resolveUnitListPrice}
 * instead of the old "first product_prices row for the company" scan that ignored the line's unit
 * (the latent pack-unit under-charge bug). Only the pricing path is exercised here; the rest of
 * {@link SalesInvoiceServiceImpl} is covered by the IT suite.
 */
@ExtendWith(MockitoExtension.class)
class SalesInvoiceServiceImplTest {

    @Mock com.erp.modules.sales.repository.SalesInvoiceRepository invoices;
    @Mock com.erp.modules.sales.repository.SalesInvoiceLineRepository lines;
    @Mock com.erp.modules.sales.repository.SalesInvoicePaymentRepository payments;
    @Mock com.erp.modules.sales.repository.TaxRateRepository taxRates;
    @Mock com.erp.modules.parties.repository.CustomerRepository customers;
    @Mock com.erp.modules.parties.repository.AgentRepository agents;
    @Mock com.erp.modules.products.repository.ProductRepository products;
    @Mock com.erp.modules.products.repository.UnitOfMeasureRepository units;
    @Mock PriceResolutionService priceResolutionService;
    @Mock com.erp.modules.products.repository.ProductBulkPackRepository bulkPacks;
    @Mock com.erp.modules.iam.repository.CompanyRepository companies;
    @Mock SalesInvoiceCodeGenerator codeGen;
    @Mock InvoiceTotalsCalculator totalsCalc;
    @Mock ScopeGuard scopeGuard;
    @Mock com.erp.platform.audit.AuditService audit;
    @Mock com.erp.platform.events.OutboxPublisher outbox;
    @Mock com.erp.modules.routes.service.RouteService routeService;
    @Mock com.erp.modules.routes.repository.RouteRepository routeRepository;
    @Mock com.erp.modules.ar.service.ArBalanceService arBalanceService;
    @Mock com.erp.platform.security.PermissionResolver permissionResolver;
    @Mock com.erp.platform.common.money.FxDocumentConverter fxConverter;
    @Mock com.erp.modules.parties.repository.PaymentTermsRepository paymentTermsRepo;
    @Mock com.erp.modules.gl.repository.JournalEntryRepository journalEntries;
    @Mock NegativeStockGuard negativeStockGuard;
    // K7: addLine consults the discount guard. Mocked (not null) because @InjectMocks would
    // otherwise pass null and every add-line test would NPE on the guard call.
    @Mock DiscountAuthorisationGuard discountGuard;
    // UAT wave 1: create() resolves the acting user's own internal agent through this, provisioning
    // one on first sale. Its own rules are pinned by InternalAgentProvisionerTest.
    @Mock InternalAgentProvisioner internalAgents;

    @InjectMocks SalesInvoiceServiceImpl service;

    private static final Long COMPANY_ID = 1L;
    private static final Long BRANCH_ID = 10L;
    private static final Long CUSTOMER_ID = 200L;
    private static final Long AGENT_ID = 300L;
    /** The approving manager's internal id, as the discount guard hands it back (K7). */
    private static final Long MANAGER_ID = 77L;

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void addLine_baseUnit_pricesAtListAmount_regressionGuardForUnderChargeFix() {
        SalesInvoice inv = invoiceWithId(500L, "INVUID0000000000000000010");
        when(invoices.findByUid(inv.getUid())).thenReturn(Optional.of(inv));

        UnitOfMeasure baseUnit = unitWithId(910L, "BASEUID0000000000000010", "PCS");
        Product product = productWithId(900L, "PRODUID00000000000000010", "PROD-0001", "Widget",
                baseUnit);
        when(products.findByCompanyIdAndUid(COMPANY_ID, "PRODUID00000000000000010"))
                .thenReturn(Optional.of(product));
        when(units.findByCompanyIdAndUid(COMPANY_ID, "BASEUID0000000000000010"))
                .thenReturn(Optional.of(baseUnit));
        when(priceResolutionService.resolveUnitListPrice(COMPANY_ID, 900L, 910L))
                .thenReturn(new UnitListPriceDto(new BigDecimal("100.0000"), false));
        when(taxRates.findByCompanyIdAndVatStatus(COMPANY_ID, VatStatus.STANDARD))
                .thenReturn(Optional.of(new TaxRate(COMPANY_ID, VatStatus.STANDARD,
                        new BigDecimal("0.1800"), 1L)));
        when(lines.findMaxLineNo(500L)).thenReturn(0);
        when(lines.save(any())).thenAnswer(a -> a.getArgument(0));

        AddInvoiceLineRequest req = new AddInvoiceLineRequest(
                "PRODUID00000000000000010", "BASEUID0000000000000010", BigDecimal.TEN, null, null);

        SalesInvoiceLineDto dto = service.addLine(inv.getUid(), req);

        assertThat(dto.unitPriceAmount()).isEqualByComparingTo("100.0000");
        assertThat(dto.qtyInBase()).isEqualByComparingTo(BigDecimal.TEN);
        verify(priceResolutionService).resolveUnitListPrice(COMPANY_ID, 900L, 910L);
    }

    @Test
    void addLine_packUnit_pricesAtExplicitPackPrice_notUnderChargedAtBaseRate() {
        SalesInvoice inv = invoiceWithId(501L, "INVUID0000000000000000011");
        when(invoices.findByUid(inv.getUid())).thenReturn(Optional.of(inv));

        UnitOfMeasure productBaseUnit = unitWithId(915L, "PCSUID0000000000000011", "PCS");
        UnitOfMeasure boxUnit = unitWithId(920L, "BOXUID00000000000000011", "BOX");
        Product product = productWithId(901L, "PRODUID00000000000000011", "PROD-0002", "Soap",
                productBaseUnit);
        when(products.findByCompanyIdAndUid(COMPANY_ID, "PRODUID00000000000000011"))
                .thenReturn(Optional.of(product));
        when(units.findByCompanyIdAndUid(COMPANY_ID, "BOXUID00000000000000011"))
                .thenReturn(Optional.of(boxUnit));
        // Base price 100; BOX pack explicitly priced at 1150 (non-linear) — never 100 (the bug).
        when(priceResolutionService.resolveUnitListPrice(COMPANY_ID, 901L, 920L))
                .thenReturn(new UnitListPriceDto(new BigDecimal("1150.0000"), false));
        when(taxRates.findByCompanyIdAndVatStatus(COMPANY_ID, VatStatus.STANDARD))
                .thenReturn(Optional.of(new TaxRate(COMPANY_ID, VatStatus.STANDARD,
                        new BigDecimal("0.1800"), 1L)));
        ProductBulkPack boxPack = new ProductBulkPack(product, boxUnit, new BigDecimal("12"), 1L);
        when(bulkPacks.findByProductId(901L)).thenReturn(List.of(boxPack));
        when(lines.findMaxLineNo(501L)).thenReturn(0);
        when(lines.save(any())).thenAnswer(a -> a.getArgument(0));

        AddInvoiceLineRequest req = new AddInvoiceLineRequest(
                "PRODUID00000000000000011", "BOXUID00000000000000011", BigDecimal.ONE, null, null);

        SalesInvoiceLineDto dto = service.addLine(inv.getUid(), req);

        assertThat(dto.unitPriceAmount()).isEqualByComparingTo("1150.0000");
        assertThat(dto.qtyInBase()).isEqualByComparingTo(new BigDecimal("12"));
        verify(priceResolutionService).resolveUnitListPrice(COMPANY_ID, 901L, 920L);
    }

    @Test
    void addLine_resolvedFromInclusiveList_snapshotsPriceInclusiveTrueOnLine() {
        // ADR-0056: the line snapshots the resolver's vatInclusive flag regardless of the amount.
        SalesInvoice inv = invoiceWithId(502L, "INVUID0000000000000000012");
        when(invoices.findByUid(inv.getUid())).thenReturn(Optional.of(inv));

        UnitOfMeasure baseUnit = unitWithId(930L, "BASEUID0000000000000012", "PCS");
        Product product = productWithId(902L, "PRODUID00000000000000012", "PROD-0003", "Shelf Item",
                baseUnit);
        when(products.findByCompanyIdAndUid(COMPANY_ID, "PRODUID00000000000000012"))
                .thenReturn(Optional.of(product));
        when(units.findByCompanyIdAndUid(COMPANY_ID, "BASEUID0000000000000012"))
                .thenReturn(Optional.of(baseUnit));
        when(priceResolutionService.resolveUnitListPrice(COMPANY_ID, 902L, 930L))
                .thenReturn(new UnitListPriceDto(new BigDecimal("1180.0000"), true));
        when(taxRates.findByCompanyIdAndVatStatus(COMPANY_ID, VatStatus.STANDARD))
                .thenReturn(Optional.of(new TaxRate(COMPANY_ID, VatStatus.STANDARD,
                        new BigDecimal("0.1800"), 1L)));
        when(lines.findMaxLineNo(502L)).thenReturn(0);
        when(lines.save(any())).thenAnswer(a -> a.getArgument(0));

        AddInvoiceLineRequest req = new AddInvoiceLineRequest(
                "PRODUID00000000000000012", "BASEUID0000000000000012", BigDecimal.ONE, null, null);

        SalesInvoiceLineDto dto = service.addLine(inv.getUid(), req);

        assertThat(dto.unitPriceAmount()).isEqualByComparingTo("1180.0000");
        assertThat(dto.priceInclusive()).isTrue();
    }

    // -------------------------------------------------------------------------
    // K7 (V95) — the manager who authorised an over-ceiling discount is stamped on the LINE, not
    // just written into the audit trail. "Who allowed this?" has to be answerable from the document.
    // -------------------------------------------------------------------------

    @Test
    void addLine_stampsTheManagerWhoAuthorisedAnOverCeilingDiscount() {
        SalesInvoice inv = givenAddableLine(503L, "INVUID0000000000000000013",
                903L, "PRODUID00000000000000013", 940L, "BASEUID0000000000000013");
        // The guard accepted the manager named on the request and handed back their internal id.
        when(discountGuard.authoriseLineDiscount(any())).thenReturn(MANAGER_ID);

        service.addLine(inv.getUid(), new AddInvoiceLineRequest(
                "PRODUID00000000000000013", "BASEUID0000000000000013", BigDecimal.ONE,
                new BigDecimal("500"), null, "MGRUID000000000000000000001"));

        assertThat(savedLine().getDiscountAuthorisedBy()).isEqualTo(MANAGER_ID);
    }

    @Test
    void addLine_withNoAuthorisationNeeded_leavesTheAuthoriserNull() {
        // The shipped default: the policy is OFF, the guard returns null, and the column stays empty
        // exactly as it was before K7. Nothing about an ordinary line changes.
        SalesInvoice inv = givenAddableLine(504L, "INVUID0000000000000000014",
                904L, "PRODUID00000000000000014", 941L, "BASEUID0000000000000014");
        // Stubbed explicitly because an unstubbed Mockito method returning Long yields 0L, not null
        // — the opposite of what the real guard does. DiscountAuthorisationGuardTest pins that the
        // OFF/within-ceiling paths genuinely return null; this asserts the service passes it through.
        when(discountGuard.authoriseLineDiscount(any())).thenReturn(null);

        service.addLine(inv.getUid(), new AddInvoiceLineRequest(
                "PRODUID00000000000000014", "BASEUID0000000000000014", BigDecimal.ONE,
                new BigDecimal("500"), null));

        assertThat(savedLine().getDiscountAuthorisedBy()).isNull();
    }

    // -------------------------------------------------------------------------
    // UAT wave 1 — pricing. A company with no price list could not invoice anything: every line was
    // refused before the price the seller had typed was ever looked at.
    // -------------------------------------------------------------------------

    @Test
    void addLine_withStatedPrice_whenCatalogueCannotPriceIt_usesTheStatedPrice() {
        asCashier();
        SalesInvoice inv = invoiceWithId(510L, "INVUID0000000000000000030");
        when(invoices.findByUid(inv.getUid())).thenReturn(Optional.of(inv));

        UnitOfMeasure baseUnit = unitWithId(960L, "BASEUID0000000000000030", "PCS");
        Product product = productWithId(910L, "PRODUID00000000000000030", "PROD-0010",
                "Unpriced Item", baseUnit);
        when(products.findByCompanyIdAndUid(COMPANY_ID, "PRODUID00000000000000030"))
                .thenReturn(Optional.of(product));
        when(units.findByCompanyIdAndUid(COMPANY_ID, "BASEUID0000000000000030"))
                .thenReturn(Optional.of(baseUnit));
        // No price list exists anywhere in this company — the live UAT condition.
        when(priceResolutionService.findUnitListPriceQuote(COMPANY_ID, 910L, 960L))
                .thenReturn(UnitPriceQuoteResult.unpriced(UnitPriceStatus.NO_PRICE));
        when(taxRates.findByCompanyIdAndVatStatus(COMPANY_ID, VatStatus.STANDARD))
                .thenReturn(Optional.of(new TaxRate(COMPANY_ID, VatStatus.STANDARD,
                        new BigDecimal("0.1800"), 1L)));
        when(lines.findMaxLineNo(510L)).thenReturn(0);
        when(lines.save(any())).thenAnswer(a -> a.getArgument(0));

        SalesInvoiceLineDto dto = service.addLine(inv.getUid(), new AddInvoiceLineRequest(
                "PRODUID00000000000000030", "BASEUID0000000000000030", BigDecimal.TEN,
                null, null, null, new BigDecimal("2500.0000")));

        assertThat(dto.unitPriceAmount()).isEqualByComparingTo("2500.0000");
        assertThat(dto.listPriceAmount()).isEqualByComparingTo("2500.0000");
        // Nothing was overridden — there was no catalogue price to override — so a cashier without
        // the price-override grant is never asked for one.
        assertThat(savedLine().isPriceOverridden()).isFalse();
    }

    @Test
    void addLine_withStatedPriceUnderAListPrice_withoutTheOverrideGrant_isRefused() {
        // The stated price must not become an ungated discount channel: changing a LISTED price is
        // permissioned, and has its own endpoint. A cashier gets told what to do instead.
        asCashier();
        SalesInvoice inv = invoiceWithId(511L, "INVUID0000000000000000031");
        when(invoices.findByUid(inv.getUid())).thenReturn(Optional.of(inv));

        UnitOfMeasure baseUnit = unitWithId(961L, "BASEUID0000000000000031", "PCS");
        Product product = productWithId(911L, "PRODUID00000000000000031", "PROD-0011", "Listed Item",
                baseUnit);
        when(products.findByCompanyIdAndUid(COMPANY_ID, "PRODUID00000000000000031"))
                .thenReturn(Optional.of(product));
        when(units.findByCompanyIdAndUid(COMPANY_ID, "BASEUID0000000000000031"))
                .thenReturn(Optional.of(baseUnit));
        when(priceResolutionService.findUnitListPriceQuote(COMPANY_ID, 911L, 961L))
                .thenReturn(UnitPriceQuoteResult.resolved(
                        new UnitPriceQuoteDto(new BigDecimal("1000.0000"), "TZS", false)));
        when(permissionResolver.hasPermission(any(), eq("SALES.INVOICE.OVERRIDE"), anyLong()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.addLine(inv.getUid(), new AddInvoiceLineRequest(
                "PRODUID00000000000000031", "BASEUID0000000000000031", BigDecimal.ONE,
                null, null, null, new BigDecimal("1.0000"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ask a supervisor");
        verify(lines, never()).save(any());
    }

    @Test
    void addLine_withStatedPriceUnderAListPrice_withTheOverrideGrant_appliesAndMarksTheLine() {
        asCashier();
        SalesInvoice inv = invoiceWithId(512L, "INVUID0000000000000000032");
        when(invoices.findByUid(inv.getUid())).thenReturn(Optional.of(inv));

        UnitOfMeasure baseUnit = unitWithId(962L, "BASEUID0000000000000032", "PCS");
        Product product = productWithId(912L, "PRODUID00000000000000032", "PROD-0012", "Listed Item",
                baseUnit);
        when(products.findByCompanyIdAndUid(COMPANY_ID, "PRODUID00000000000000032"))
                .thenReturn(Optional.of(product));
        when(units.findByCompanyIdAndUid(COMPANY_ID, "BASEUID0000000000000032"))
                .thenReturn(Optional.of(baseUnit));
        when(priceResolutionService.findUnitListPriceQuote(COMPANY_ID, 912L, 962L))
                .thenReturn(UnitPriceQuoteResult.resolved(
                        new UnitPriceQuoteDto(new BigDecimal("1000.0000"), "TZS", false)));
        when(permissionResolver.hasPermission(any(), eq("SALES.INVOICE.OVERRIDE"), anyLong()))
                .thenReturn(true);
        when(taxRates.findByCompanyIdAndVatStatus(COMPANY_ID, VatStatus.STANDARD))
                .thenReturn(Optional.of(new TaxRate(COMPANY_ID, VatStatus.STANDARD,
                        new BigDecimal("0.1800"), 1L)));
        when(lines.findMaxLineNo(512L)).thenReturn(0);
        when(lines.save(any())).thenAnswer(a -> a.getArgument(0));

        SalesInvoiceLineDto dto = service.addLine(inv.getUid(), new AddInvoiceLineRequest(
                "PRODUID00000000000000032", "BASEUID0000000000000032", BigDecimal.ONE,
                null, null, null, new BigDecimal("900.0000")));

        assertThat(dto.unitPriceAmount()).isEqualByComparingTo("900.0000");
        // The list price is still snapshotted, so the discount off list stays visible on the doc.
        assertThat(dto.listPriceAmount()).isEqualByComparingTo("1000.0000");
        assertThat(savedLine().isPriceOverridden()).isTrue();

        // ...and it is findable AS an override. Compliance reviews filter on the override action;
        // recording it only as "line added, priceOverridden=true" hid every override taken on this
        // path — which, now that add-line can state a price, is the path most of them take.
        assertThat(auditedActions()).contains("SALES.INVOICE.LINE.OVERRIDE");
        // The "a line was added" fact is not traded away for it — both are recorded.
        assertThat(auditedActions()).contains("SALES.INVOICE.LINE.ADD");
        assertThat(overrideAuditDetail())
                .containsEntry("listPrice", "1000.0000")
                .containsEntry("appliedPrice", "900.0000")
                // Tells the two override routes apart without a second action code.
                .containsEntry("overriddenOn", "LINE_ADD");
    }

    @Test
    void addLine_atTheCataloguePrice_recordsNoOverride() {
        // The other half of the rule: an ordinary line must not clutter the override trail, or the
        // action stops meaning anything. Nothing was overridden, so nothing is recorded as one.
        SalesInvoice inv = givenAddableLine(513L, "INVUID0000000000000000033",
                913L, "PRODUID00000000000000033", 963L, "BASEUID0000000000000033");

        service.addLine(inv.getUid(), new AddInvoiceLineRequest(
                "PRODUID00000000000000033", "BASEUID0000000000000033", BigDecimal.ONE, null, null));

        assertThat(auditedActions())
                .contains("SALES.INVOICE.LINE.ADD")
                .doesNotContain("SALES.INVOICE.LINE.OVERRIDE");
    }

    // -------------------------------------------------------------------------
    // UAT wave 1 — the mandatory sales agent. Zero agents existed company-wide and a cashier could
    // not even list them, so the "select a sales agent" remedy pointed at a locked door.
    // -------------------------------------------------------------------------

    @Test
    void create_withNoAgentNamed_usesTheAgentProvisionedForTheActingUser() {
        asCashier();
        Long provisionedAgentId = 4242L;
        when(companies.findByUid("COMPUID00000000000000001")).thenReturn(Optional.of(company()));
        when(customers.findByCompanyIdAndUid(COMPANY_ID, "CUSTUID00000000000000001"))
                .thenReturn(Optional.of(customer()));
        when(internalAgents.resolveOrProvision(eq(COMPANY_ID), any()))
                .thenReturn(Optional.of(provisionedAgentId));
        when(invoices.save(any())).thenAnswer(a -> a.getArgument(0));

        service.create(new CreateSalesInvoiceRequest("COMPUID00000000000000001",
                "CUSTUID00000000000000001", null, "TZS", null, null));

        ArgumentCaptor<SalesInvoice> captor = ArgumentCaptor.forClass(SalesInvoice.class);
        verify(invoices).save(captor.capture());
        assertThat(captor.getValue().getAgentId()).isEqualTo(provisionedAgentId);
    }

    @Test
    void create_whenTheActingUserCannotHoldAnAgent_stillRefusesWithAnActionableMessage() {
        // Root, or anyone who is not company staff: no agent can be provisioned for them, and the
        // rule (a sale names exactly one agent) is not weakened to let the sale through.
        asCashier();
        when(companies.findByUid("COMPUID00000000000000001")).thenReturn(Optional.of(company()));
        when(customers.findByCompanyIdAndUid(COMPANY_ID, "CUSTUID00000000000000001"))
                .thenReturn(Optional.of(customer()));
        when(internalAgents.resolveOrProvision(eq(COMPANY_ID), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new CreateSalesInvoiceRequest(
                "COMPUID00000000000000001", "CUSTUID00000000000000001", null, "TZS", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Select a sales agent");
        verify(invoices, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** A NON-root principal: root bypasses every permission check and would mask an RBAC gap. */
    private static void asCashier() {
        RequestContext.set(new RequestContext.Principal(
                77L, "cashier", false, COMPANY_ID, BRANCH_ID, "127.0.0.1"));
    }

    private static com.erp.modules.iam.domain.entity.Company company() {
        var c = new com.erp.modules.iam.domain.entity.Company(null, "C1", "Acme Ltd");
        ReflectionTestUtils.setField(c, "id", COMPANY_ID);
        ReflectionTestUtils.setField(c, "uid", "COMPUID00000000000000001");
        return c;
    }

    private static com.erp.modules.parties.domain.entity.Customer customer() {
        var cust = new com.erp.modules.parties.domain.entity.Customer(COMPANY_ID, "CUST-0001",
                com.erp.modules.parties.domain.enums.PartyType.BUSINESS, "Walk-in",
                com.erp.modules.parties.domain.enums.CustomerKind.CASH_WALK_IN, 1L);
        ReflectionTestUtils.setField(cust, "id", CUSTOMER_ID);
        ReflectionTestUtils.setField(cust, "uid", "CUSTUID00000000000000001");
        return cust;
    }

    /** Every audit action the service recorded during the call, in order. */
    private List<String> auditedActions() {
        return recordedAuditEvents().stream()
                .map(com.erp.platform.audit.AuditEvent::action)
                .toList();
    }

    /** The detail map of the price-override event — what a compliance review actually reads. */
    private java.util.Map<String, Object> overrideAuditDetail() {
        return recordedAuditEvents().stream()
                .filter(e -> "SALES.INVOICE.LINE.OVERRIDE".equals(e.action()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no price-override audit event was recorded"))
                .detail();
    }

    private List<com.erp.platform.audit.AuditEvent> recordedAuditEvents() {
        ArgumentCaptor<com.erp.platform.audit.AuditEvent> captor =
                ArgumentCaptor.forClass(com.erp.platform.audit.AuditEvent.class);
        verify(audit, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
        return captor.getAllValues();
    }

    /** The line the service actually persisted. */
    private com.erp.modules.sales.domain.entity.SalesInvoiceLine savedLine() {
        ArgumentCaptor<com.erp.modules.sales.domain.entity.SalesInvoiceLine> captor =
                ArgumentCaptor.forClass(com.erp.modules.sales.domain.entity.SalesInvoiceLine.class);
        verify(lines).save(captor.capture());
        return captor.getValue();
    }

    /** Stubs everything {@code addLine} needs for one 1 000/unit STANDARD-VAT product. */
    private SalesInvoice givenAddableLine(Long invoiceId, String invoiceUid,
                                          Long productId, String productUid,
                                          Long unitId, String unitUid) {
        SalesInvoice inv = invoiceWithId(invoiceId, invoiceUid);
        when(invoices.findByUid(invoiceUid)).thenReturn(Optional.of(inv));

        UnitOfMeasure baseUnit = unitWithId(unitId, unitUid, "PCS");
        Product product = productWithId(productId, productUid, "PROD-K7", "Sugar 1kg", baseUnit);
        when(products.findByCompanyIdAndUid(COMPANY_ID, productUid)).thenReturn(Optional.of(product));
        when(units.findByCompanyIdAndUid(COMPANY_ID, unitUid)).thenReturn(Optional.of(baseUnit));
        when(priceResolutionService.resolveUnitListPrice(COMPANY_ID, productId, unitId))
                .thenReturn(new UnitListPriceDto(new BigDecimal("1000.0000"), false));
        when(taxRates.findByCompanyIdAndVatStatus(COMPANY_ID, VatStatus.STANDARD))
                .thenReturn(Optional.of(new TaxRate(COMPANY_ID, VatStatus.STANDARD,
                        new BigDecimal("0.1800"), 1L)));
        when(lines.findMaxLineNo(invoiceId)).thenReturn(0);
        when(lines.save(any())).thenAnswer(a -> a.getArgument(0));
        return inv;
    }

    private static SalesInvoice invoiceWithId(Long id, String uid) {
        SalesInvoice inv = new SalesInvoice(COMPANY_ID, BRANCH_ID, CUSTOMER_ID, AGENT_ID, "TZS", 1L);
        ReflectionTestUtils.setField(inv, "id", id);
        ReflectionTestUtils.setField(inv, "uid", uid);
        return inv;
    }

    private static UnitOfMeasure unitWithId(Long id, String uid, String code) {
        UnitOfMeasure unit = new UnitOfMeasure(COMPANY_ID, code, code, 1L);
        ReflectionTestUtils.setField(unit, "id", id);
        ReflectionTestUtils.setField(unit, "uid", uid);
        return unit;
    }

    private static Product productWithId(Long id, String uid, String code, String name,
                                         UnitOfMeasure baseUnit) {
        Product product = new Product(COMPANY_ID, code, name, ProductType.GOODS,
                true, true, baseUnit, 1L);
        ReflectionTestUtils.setField(product, "id", id);
        ReflectionTestUtils.setField(product, "uid", uid);
        return product;
    }
}
