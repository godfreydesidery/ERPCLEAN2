package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBulkPack;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.products.service.PriceResolutionService;
import com.erp.modules.sales.domain.dto.AddQuotationLineRequest;
import com.erp.modules.sales.domain.dto.QuotationLineDto;
import com.erp.modules.sales.domain.entity.Quotation;
import com.erp.modules.sales.domain.entity.TaxRate;
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
                .thenReturn(new BigDecimal("100.0000"));
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
                .thenReturn(new BigDecimal("1150.0000"));
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
