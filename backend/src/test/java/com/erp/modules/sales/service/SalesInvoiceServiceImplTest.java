package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBulkPack;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.PriceResolutionService;
import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
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

    @InjectMocks SalesInvoiceServiceImpl service;

    private static final Long COMPANY_ID = 1L;
    private static final Long BRANCH_ID = 10L;
    private static final Long CUSTOMER_ID = 200L;
    private static final Long AGENT_ID = 300L;

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
                .thenReturn(new BigDecimal("100.0000"));
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
                .thenReturn(new BigDecimal("1150.0000"));
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
