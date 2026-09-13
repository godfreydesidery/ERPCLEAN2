package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Supplier;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.purchases.domain.dto.PurchaseCostSuggestionDto;
import com.erp.modules.purchases.domain.entity.PurchaseOrderLine;
import com.erp.modules.purchases.domain.enums.PurchaseCostSource;
import com.erp.modules.purchases.repository.PurchaseOrderLineRepository;
import com.erp.modules.purchases.repository.PurchaseOrderRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.Money;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for the DIRECT-RECEIPT entry point of the unit-cost suggestion (K-2026-08-30 #4:
 * "have items pick cost price already existing in the system, not having to input the cost price
 * all the time").
 *
 * <p>The PO entry point is unchanged and already covered by the screen it feeds; what is new here
 * is resolving company + supplier from the request instead of from a loaded order, which is where
 * a scoping mistake would let one company read another's purchase prices.
 */
class PurchaseCostSuggestionServiceImplTest {

    private static final String COMPANY_UID  = "CO-UID-1";
    private static final String SUPPLIER_UID = "SUP-UID-1";
    private static final String PRODUCT_UID  = "PRD-UID-1";
    private static final String UNIT_UID     = "UOM-UID-1";

    private static final Long COMPANY_ID  = 10L;
    private static final Long SUPPLIER_ID = 2L;

    private PurchaseOrderRepository     orders;
    private PurchaseOrderLineRepository lines;
    private ProductRepository           products;
    private UnitOfMeasureRepository     units;
    private CompanyRepository           companies;
    private SupplierRepository          suppliers;
    private SupplierPriceReader         supplierPrices;
    private ScopeGuard                  scopeGuard;

    private PurchaseCostSuggestionServiceImpl service;

    private Product       product;
    private UnitOfMeasure baseUnit;

    @BeforeEach
    void setUp() {
        orders         = mock(PurchaseOrderRepository.class);
        lines          = mock(PurchaseOrderLineRepository.class);
        products       = mock(ProductRepository.class);
        units          = mock(UnitOfMeasureRepository.class);
        companies      = mock(CompanyRepository.class);
        suppliers      = mock(SupplierRepository.class);
        supplierPrices = mock(SupplierPriceReader.class);
        scopeGuard     = mock(ScopeGuard.class);

        service = new PurchaseCostSuggestionServiceImpl(
                orders, lines, products, units, companies, suppliers, supplierPrices, scopeGuard);

        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        when(companies.findByUid(COMPANY_UID)).thenReturn(Optional.of(company));

        Supplier supplier = mock(Supplier.class);
        when(supplier.getId()).thenReturn(SUPPLIER_ID);
        when(suppliers.findByCompanyIdAndUid(COMPANY_ID, SUPPLIER_UID))
                .thenReturn(Optional.of(supplier));

        baseUnit = mock(UnitOfMeasure.class);
        when(baseUnit.getId()).thenReturn(1L);
        product = mock(Product.class);
        when(product.getId()).thenReturn(5L);
        when(product.getBaseUnit()).thenReturn(baseUnit);

        when(products.findByCompanyIdAndUid(COMPANY_ID, PRODUCT_UID)).thenReturn(Optional.of(product));
        when(units.findByCompanyIdAndUid(COMPANY_ID, UNIT_UID)).thenReturn(Optional.of(baseUnit));
    }

    /** RequestContext is a ThreadLocal — leaving it set would bleed into the next test. */
    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Scope
    // -------------------------------------------------------------------------

    @Test
    void directReceipt_assertsScopeAgainstTheLoadedCompany() {
        givenNoSupplierHistory();
        when(product.getCost()).thenReturn(new Money(new BigDecimal("4500.00"), "TZS"));

        service.suggestUnitCostForDirectReceipt(COMPANY_UID, SUPPLIER_UID, PRODUCT_UID, UNIT_UID);

        // The company id comes from the LOADED company row, never from anything the caller sent.
        verify(scopeGuard).assertCanActIn(any(), eq(COMPANY_ID));
    }

    @Test
    void directReceipt_unknownCompany_throwsNotFound() {
        when(companies.findByUid("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.suggestUnitCostForDirectReceipt("NOPE", SUPPLIER_UID, PRODUCT_UID, UNIT_UID))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(products);
    }

    @Test
    void directReceipt_supplierFromAnotherCompany_throwsNotFound() {
        when(suppliers.findByCompanyIdAndUid(COMPANY_ID, "SUP-ELSEWHERE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.suggestUnitCostForDirectReceipt(
                COMPANY_UID, "SUP-ELSEWHERE", PRODUCT_UID, UNIT_UID))
                .isInstanceOf(NotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // The fallback chain
    // -------------------------------------------------------------------------

    @Test
    void directReceipt_noSupplierHistory_fallsBackToTheProductCost() {
        givenNoSupplierHistory();
        when(product.getCost()).thenReturn(new Money(new BigDecimal("4500.00"), "TZS"));

        Optional<PurchaseCostSuggestionDto> suggestion = service.suggestUnitCostForDirectReceipt(
                COMPANY_UID, SUPPLIER_UID, PRODUCT_UID, UNIT_UID);

        assertThat(suggestion).isPresent();
        assertThat(suggestion.get().amount()).isEqualByComparingTo("4500.00");
        assertThat(suggestion.get().currency()).isEqualTo("TZS");
        assertThat(suggestion.get().source()).isEqualTo(PurchaseCostSource.PRODUCT_COST);
    }

    /**
     * The storekeeper types the delivery in whatever order the paperwork arrives, so items are
     * often picked before the supplier. A blank supplier must not be an error — it skips the two
     * supplier-specific sources and lets the product master still answer.
     */
    @Test
    void directReceipt_blankSupplier_skipsSupplierSourcesAndStillAnswers() {
        when(product.getCost()).thenReturn(new Money(new BigDecimal("4500.00"), "TZS"));

        Optional<PurchaseCostSuggestionDto> suggestion = service.suggestUnitCostForDirectReceipt(
                COMPANY_UID, "  ", PRODUCT_UID, UNIT_UID);

        assertThat(suggestion).isPresent();
        assertThat(suggestion.get().source()).isEqualTo(PurchaseCostSource.PRODUCT_COST);
        verifyNoInteractions(suppliers);
        // Never queried with a null supplier key, which would silently match nothing.
        verify(supplierPrices, never()).lastQuotedLine(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void directReceipt_nullSupplier_isTreatedAsAbsent() {
        when(product.getCost()).thenReturn(new Money(new BigDecimal("4500.00"), "TZS"));

        Optional<PurchaseCostSuggestionDto> suggestion = service.suggestUnitCostForDirectReceipt(
                COMPANY_UID, null, PRODUCT_UID, UNIT_UID);

        assertThat(suggestion).isPresent();
        verifyNoInteractions(suppliers);
    }

    /**
     * No source has a price. The field must be left BLANK rather than defaulted to zero: a zero
     * cost received into stock drags the moving average down and reports the goods as free.
     */
    @Test
    void directReceipt_nothingKnown_suggestsNothingRatherThanZero() {
        givenNoSupplierHistory();
        when(product.getCost()).thenReturn(null);

        Optional<PurchaseCostSuggestionDto> suggestion = service.suggestUnitCostForDirectReceipt(
                COMPANY_UID, SUPPLIER_UID, PRODUCT_UID, UNIT_UID);

        assertThat(suggestion).isEmpty();
    }

    @Test
    void directReceipt_zeroProductCost_suggestsNothing() {
        givenNoSupplierHistory();
        when(product.getCost()).thenReturn(new Money(BigDecimal.ZERO, "TZS"));

        assertThat(service.suggestUnitCostForDirectReceipt(
                COMPANY_UID, SUPPLIER_UID, PRODUCT_UID, UNIT_UID)).isEmpty();
    }

    /**
     * Prices are per unit. A carton price must never be offered for a line received per piece, so
     * the product-master cost applies only to the base unit it is expressed in.
     */
    @Test
    void directReceipt_nonBaseUnit_doesNotOfferTheProductCost() {
        givenNoSupplierHistory();
        when(product.getCost()).thenReturn(new Money(new BigDecimal("4500.00"), "TZS"));

        UnitOfMeasure carton = mock(UnitOfMeasure.class);
        when(carton.getId()).thenReturn(99L);
        when(units.findByCompanyIdAndUid(COMPANY_ID, "UOM-CARTON")).thenReturn(Optional.of(carton));

        assertThat(service.suggestUnitCostForDirectReceipt(
                COMPANY_UID, SUPPLIER_UID, PRODUCT_UID, "UOM-CARTON")).isEmpty();
    }

    @Test
    void directReceipt_unknownProduct_throwsNotFound() {
        when(products.findByCompanyIdAndUid(COMPANY_ID, "PRD-NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.suggestUnitCostForDirectReceipt(
                COMPANY_UID, SUPPLIER_UID, "PRD-NOPE", UNIT_UID))
                .isInstanceOf(NotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** This supplier has never quoted and never been bought from for this product/unit. */
    private void givenNoSupplierHistory() {
        when(supplierPrices.lastQuotedLine(anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(Optional.empty());
        when(lines.findLastPurchasedLine(anyLong(), anyLong(), anyLong(), anyLong(), any(Pageable.class)))
                .thenReturn(List.<PurchaseOrderLine>of());
    }
}
