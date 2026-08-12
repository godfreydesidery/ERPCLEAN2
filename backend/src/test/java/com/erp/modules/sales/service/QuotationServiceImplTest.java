package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.products.domain.dto.UnitListPriceDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteResult;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBulkPack;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.UnitPriceStatus;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.products.service.PriceResolutionService;
import com.erp.modules.sales.domain.dto.AddQuotationLineRequest;
import com.erp.modules.sales.domain.dto.QuotationLineDto;
import com.erp.modules.sales.domain.entity.Quotation;
import com.erp.modules.sales.domain.entity.QuotationLine;
import com.erp.modules.sales.domain.entity.TaxRate;
import com.erp.modules.sales.domain.enums.QuotationStatus;
import com.erp.modules.sales.repository.QuotationLineRepository;
import com.erp.modules.sales.repository.QuotationRepository;
import com.erp.modules.sales.repository.TaxRateRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link QuotationServiceImpl#addLine} — ADR-0048 D-1 (multi-unit / per-pack
 * pricing). {@code addLine} now delegates to {@code PriceResolutionService.resolveUnitListPrice}
 * instead of the old "first product_prices row for the company" scan that ignored the line's unit
 * (the latent pack-unit under-charge bug).
 */
@ExtendWith(MockitoExtension.class)
class QuotationServiceImplTest {

    @Mock QuotationRepository quotations;
    @Mock QuotationLineRepository quotationLines;
    @Mock CustomerRepository customers;
    @Mock AgentRepository agents;
    @Mock CompanyRepository companies;
    @Mock ProductRepository products;
    @Mock UnitOfMeasureRepository units;
    @Mock PriceResolutionService priceResolutionService;
    @Mock ProductBulkPackRepository bulkPacks;
    @Mock TaxRateRepository taxRates;
    @Mock SalesOrderService salesOrderService;
    @Mock OrderToCashNumberGenerator numberGen;
    @Mock SalesOrderTotalsCalculator totalsCalc;
    @Mock ScopeGuard scopeGuard;
    @Mock AuditService audit;

    @InjectMocks QuotationServiceImpl service;

    private static final Long COMPANY_ID = 1L;
    private static final Long BRANCH_ID = 10L;
    private static final Long CUSTOMER_ID = 200L;

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void addLine_baseUnit_pricesAtListAmount_regressionGuardForUnderChargeFix() {
        Quotation q = quotationWithId(500L, "QUOUID0000000000000000010");
        when(quotations.findByUid(q.getUid())).thenReturn(Optional.of(q));

        UnitOfMeasure baseUnit = unitWithId(910L, "BASEUID0000000000000020", "PCS");
        Product product = productWithId(900L, "PRODUID00000000000000020", "PROD-0001", "Widget",
                baseUnit);
        when(products.findByCompanyIdAndUid(COMPANY_ID, "PRODUID00000000000000020"))
                .thenReturn(Optional.of(product));
        when(units.findByCompanyIdAndUid(COMPANY_ID, "BASEUID0000000000000020"))
                .thenReturn(Optional.of(baseUnit));
        when(priceResolutionService.resolveUnitListPrice(COMPANY_ID, 900L, 910L))
                .thenReturn(new UnitListPriceDto(new BigDecimal("100.0000"), false));
        when(taxRates.findByCompanyIdAndVatStatus(COMPANY_ID, VatStatus.STANDARD))
                .thenReturn(Optional.of(new TaxRate(COMPANY_ID, VatStatus.STANDARD,
                        new BigDecimal("0.1800"), 1L)));
        when(quotationLines.findMaxLineNo(500L)).thenReturn(0);
        when(quotationLines.save(any())).thenAnswer(a -> a.getArgument(0));
        when(quotationLines.findByQuotationIdOrderByLineNo(500L)).thenReturn(List.of());

        AddQuotationLineRequest req = new AddQuotationLineRequest(
                "PRODUID00000000000000020", "BASEUID0000000000000020", BigDecimal.TEN, null, null, null);

        QuotationLineDto dto = service.addLine(q.getUid(), req);

        assertThat(dto.unitPriceAmount()).isEqualByComparingTo("100.0000");
        assertThat(dto.qtyInBase()).isEqualByComparingTo(BigDecimal.TEN);
        verify(priceResolutionService).resolveUnitListPrice(COMPANY_ID, 900L, 910L);
    }

    @Test
    void addLine_packUnit_pricesAtExplicitPackPrice_notUnderChargedAtBaseRate() {
        Quotation q = quotationWithId(501L, "QUOUID0000000000000000011");
        when(quotations.findByUid(q.getUid())).thenReturn(Optional.of(q));

        UnitOfMeasure productBaseUnit = unitWithId(915L, "PCSUID0000000000000021", "PCS");
        UnitOfMeasure boxUnit = unitWithId(920L, "BOXUID00000000000000021", "BOX");
        Product product = productWithId(901L, "PRODUID00000000000000021", "PROD-0002", "Soap",
                productBaseUnit);
        when(products.findByCompanyIdAndUid(COMPANY_ID, "PRODUID00000000000000021"))
                .thenReturn(Optional.of(product));
        when(units.findByCompanyIdAndUid(COMPANY_ID, "BOXUID00000000000000021"))
                .thenReturn(Optional.of(boxUnit));
        when(priceResolutionService.resolveUnitListPrice(COMPANY_ID, 901L, 920L))
                .thenReturn(new UnitListPriceDto(new BigDecimal("1150.0000"), false));
        when(taxRates.findByCompanyIdAndVatStatus(COMPANY_ID, VatStatus.STANDARD))
                .thenReturn(Optional.of(new TaxRate(COMPANY_ID, VatStatus.STANDARD,
                        new BigDecimal("0.1800"), 1L)));
        ProductBulkPack boxPack = new ProductBulkPack(product, boxUnit, new BigDecimal("12"), 1L);
        when(bulkPacks.findByProductId(901L)).thenReturn(List.of(boxPack));
        when(quotationLines.findMaxLineNo(501L)).thenReturn(0);
        when(quotationLines.save(any())).thenAnswer(a -> a.getArgument(0));
        when(quotationLines.findByQuotationIdOrderByLineNo(501L)).thenReturn(List.of());

        AddQuotationLineRequest req = new AddQuotationLineRequest(
                "PRODUID00000000000000021", "BOXUID00000000000000021", BigDecimal.ONE, null, null, null);

        QuotationLineDto dto = service.addLine(q.getUid(), req);

        assertThat(dto.unitPriceAmount()).isEqualByComparingTo("1150.0000");
        assertThat(dto.qtyInBase()).isEqualByComparingTo(new BigDecimal("12"));
        verify(priceResolutionService).resolveUnitListPrice(COMPANY_ID, 901L, 920L);
    }

    @Test
    void addLine_resolvedFromInclusiveList_snapshotsPriceInclusiveTrueOnLine() {
        // ADR-0056: the line snapshots the resolver's vatInclusive flag regardless of the amount.
        Quotation q = quotationWithId(502L, "QUOUID0000000000000000012");
        when(quotations.findByUid(q.getUid())).thenReturn(Optional.of(q));

        UnitOfMeasure baseUnit = unitWithId(950L, "BASEUID0000000000000022", "PCS");
        Product product = productWithId(902L, "PRODUID00000000000000022", "PROD-0003", "Shelf Item",
                baseUnit);
        when(products.findByCompanyIdAndUid(COMPANY_ID, "PRODUID00000000000000022"))
                .thenReturn(Optional.of(product));
        when(units.findByCompanyIdAndUid(COMPANY_ID, "BASEUID0000000000000022"))
                .thenReturn(Optional.of(baseUnit));
        when(priceResolutionService.resolveUnitListPrice(COMPANY_ID, 902L, 950L))
                .thenReturn(new UnitListPriceDto(new BigDecimal("1180.0000"), true));
        when(taxRates.findByCompanyIdAndVatStatus(COMPANY_ID, VatStatus.STANDARD))
                .thenReturn(Optional.of(new TaxRate(COMPANY_ID, VatStatus.STANDARD,
                        new BigDecimal("0.1800"), 1L)));
        when(quotationLines.findMaxLineNo(502L)).thenReturn(0);
        when(quotationLines.save(any())).thenAnswer(a -> a.getArgument(0));
        when(quotationLines.findByQuotationIdOrderByLineNo(502L)).thenReturn(List.of());

        AddQuotationLineRequest req = new AddQuotationLineRequest(
                "PRODUID00000000000000022", "BASEUID0000000000000022", BigDecimal.ONE, null, null, null);

        QuotationLineDto dto = service.addLine(q.getUid(), req);

        assertThat(dto.unitPriceAmount()).isEqualByComparingTo("1180.0000");
        assertThat(dto.priceInclusive()).isTrue();
    }

    // -------------------------------------------------------------------------
    // UAT wave 1 — a company with no price list could not quote anything. The stated unit price was
    // accepted by the API and never read, because the throwing list-price lookup ran first.
    // -------------------------------------------------------------------------

    @Test
    void addLine_withStatedPrice_whenCatalogueCannotPriceIt_usesTheStatedPrice() {
        asCashier();
        Quotation q = quotationWithId(510L, "QUOUID0000000000000000030");
        when(quotations.findByUid(q.getUid())).thenReturn(Optional.of(q));

        UnitOfMeasure baseUnit = unitWithId(960L, "BASEUID0000000000000030", "PCS");
        Product product = productWithId(910L, "PRODUID00000000000000030", "PROD-0010", "Unpriced Item",
                baseUnit);
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
        when(quotationLines.findMaxLineNo(510L)).thenReturn(0);
        when(quotationLines.save(any())).thenAnswer(a -> a.getArgument(0));
        when(quotationLines.findByQuotationIdOrderByLineNo(510L)).thenReturn(List.of());

        QuotationLineDto dto = service.addLine(q.getUid(), new AddQuotationLineRequest(
                "PRODUID00000000000000030", "BASEUID0000000000000030", BigDecimal.TEN,
                new BigDecimal("2500.0000"), null, null));

        assertThat(dto.unitPriceAmount()).isEqualByComparingTo("2500.0000");
        // With no catalogue price to quote against, the stated price is also the line's list price.
        assertThat(dto.listPriceAmount()).isEqualByComparingTo("2500.0000");
    }

    @Test
    void addLine_withStatedPrice_whenAListPriceExists_stillSnapshotsTheListPrice() {
        asCashier();
        Quotation q = quotationWithId(511L, "QUOUID0000000000000000031");
        when(quotations.findByUid(q.getUid())).thenReturn(Optional.of(q));

        UnitOfMeasure baseUnit = unitWithId(961L, "BASEUID0000000000000031", "PCS");
        Product product = productWithId(911L, "PRODUID00000000000000031", "PROD-0011", "Listed Item",
                baseUnit);
        when(products.findByCompanyIdAndUid(COMPANY_ID, "PRODUID00000000000000031"))
                .thenReturn(Optional.of(product));
        when(units.findByCompanyIdAndUid(COMPANY_ID, "BASEUID0000000000000031"))
                .thenReturn(Optional.of(baseUnit));
        when(priceResolutionService.findUnitListPriceQuote(COMPANY_ID, 911L, 961L))
                .thenReturn(UnitPriceQuoteResult.resolved(
                        new UnitPriceQuoteDto(new BigDecimal("100.0000"), "TZS", false)));
        when(taxRates.findByCompanyIdAndVatStatus(COMPANY_ID, VatStatus.STANDARD))
                .thenReturn(Optional.of(new TaxRate(COMPANY_ID, VatStatus.STANDARD,
                        new BigDecimal("0.1800"), 1L)));
        when(quotationLines.findMaxLineNo(511L)).thenReturn(0);
        when(quotationLines.save(any())).thenAnswer(a -> a.getArgument(0));
        when(quotationLines.findByQuotationIdOrderByLineNo(511L)).thenReturn(List.of());

        QuotationLineDto dto = service.addLine(q.getUid(), new AddQuotationLineRequest(
                "PRODUID00000000000000031", "BASEUID0000000000000031", BigDecimal.ONE,
                new BigDecimal("90.0000"), null, null));

        // The negotiated price applies, but the list price is still recorded — otherwise the
        // discount off list is invisible on the quote.
        assertThat(dto.unitPriceAmount()).isEqualByComparingTo("90.0000");
        assertThat(dto.listPriceAmount()).isEqualByComparingTo("100.0000");
    }

    @Test
    void addLine_withNoStatedPrice_andNoCataloguePrice_stillRefusesWithBothRemedies() {
        asCashier();
        Quotation q = quotationWithId(512L, "QUOUID0000000000000000032");
        when(quotations.findByUid(q.getUid())).thenReturn(Optional.of(q));

        UnitOfMeasure baseUnit = unitWithId(962L, "BASEUID0000000000000032", "PCS");
        Product product = productWithId(912L, "PRODUID00000000000000032", "PROD-0012", "Unpriced Item",
                baseUnit);
        when(products.findByCompanyIdAndUid(COMPANY_ID, "PRODUID00000000000000032"))
                .thenReturn(Optional.of(product));
        when(units.findByCompanyIdAndUid(COMPANY_ID, "BASEUID0000000000000032"))
                .thenReturn(Optional.of(baseUnit));
        when(priceResolutionService.resolveUnitListPrice(COMPANY_ID, 912L, 962L))
                .thenThrow(new IllegalArgumentException(
                        "This product has no price yet — no price list is configured for this "
                        + "company. Set up a price list, or enter a unit price on the line."));

        assertThatThrownBy(() -> service.addLine(q.getUid(), new AddQuotationLineRequest(
                "PRODUID00000000000000032", "BASEUID0000000000000032", BigDecimal.ONE,
                null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enter a unit price on the line");
        verify(quotationLines, never()).save(any());
    }

    @Test
    void addLine_withNegativeStatedPrice_isRefusedBeforeItCanStandInForAListPrice() {
        asCashier();
        Quotation q = quotationWithId(513L, "QUOUID0000000000000000033");
        when(quotations.findByUid(q.getUid())).thenReturn(Optional.of(q));

        UnitOfMeasure baseUnit = unitWithId(963L, "BASEUID0000000000000033", "PCS");
        Product product = productWithId(913L, "PRODUID00000000000000033", "PROD-0013", "Any Item",
                baseUnit);
        when(products.findByCompanyIdAndUid(COMPANY_ID, "PRODUID00000000000000033"))
                .thenReturn(Optional.of(product));
        when(units.findByCompanyIdAndUid(COMPANY_ID, "BASEUID0000000000000033"))
                .thenReturn(Optional.of(baseUnit));

        assertThatThrownBy(() -> service.addLine(q.getUid(), new AddQuotationLineRequest(
                "PRODUID00000000000000033", "BASEUID0000000000000033", BigDecimal.ONE,
                new BigDecimal("-5"), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be negative");
        verify(quotationLines, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // UAT wave 1 — an empty quotation could be sent to a customer: number allocated, sentAt
    // stamped, and no way back (SENT can only be accepted or rejected, never edited).
    // -------------------------------------------------------------------------

    @Test
    void send_withNoLines_isRefusedAndNeitherNumbersNorStampsTheQuotation() {
        asCashier();
        Quotation q = quotationWithId(520L, "QUOUID0000000000000000040");
        when(quotations.findByUid(q.getUid())).thenReturn(Optional.of(q));
        when(quotationLines.findByQuotationIdOrderByLineNo(520L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.send(q.getUid()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Add at least one line");

        assertThat(q.getStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(q.getQuoteNumber()).isNull();
        assertThat(q.getSentAt()).isNull();
        verify(numberGen, never()).nextQuote(any());
    }

    @Test
    void send_withAtLeastOneLine_stillSends() {
        asCashier();
        Quotation q = quotationWithId(521L, "QUOUID0000000000000000041");
        when(quotations.findByUid(q.getUid())).thenReturn(Optional.of(q));
        QuotationLine line = new QuotationLine(521L, COMPANY_ID, BRANCH_ID, (short) 1,
                900L, "PROD-0001", "Widget", 910L, "PCS",
                BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("100"), new BigDecimal("100"),
                VatStatus.STANDARD, new BigDecimal("0.1800"), "TZS", 1L);
        when(quotationLines.findByQuotationIdOrderByLineNo(521L)).thenReturn(List.of(line));
        when(numberGen.nextQuote(COMPANY_ID)).thenReturn("QT-0001");

        service.send(q.getUid());

        assertThat(q.getStatus()).isEqualTo(QuotationStatus.SENT);
        assertThat(q.getQuoteNumber()).isEqualTo("QT-0001");
        assertThat(q.getSentAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** A NON-root principal: root bypasses permission checks and would hide RBAC regressions. */
    private static void asCashier() {
        RequestContext.set(new RequestContext.Principal(
                77L, "cashier", false, COMPANY_ID, BRANCH_ID, "127.0.0.1"));
    }

    private static Quotation quotationWithId(Long id, String uid) {
        Quotation q = new Quotation(COMPANY_ID, BRANCH_ID, CUSTOMER_ID, null,
                "TZS", LocalDate.now(), LocalDate.now().plusDays(30), 1L);
        ReflectionTestUtils.setField(q, "id", id);
        ReflectionTestUtils.setField(q, "uid", uid);
        return q;
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
