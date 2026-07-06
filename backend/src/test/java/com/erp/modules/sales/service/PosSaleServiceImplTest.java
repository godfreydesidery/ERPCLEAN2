package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.RestrictedKind;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.sales.domain.dto.PosSaleRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.VoidInvoiceRequest;
import com.erp.modules.sales.domain.entity.PosSession;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.enums.DocumentOrigin;
import com.erp.modules.sales.domain.enums.PosSessionStatus;
import com.erp.modules.sales.repository.PosSaleIdempotencyRepository;
import com.erp.modules.sales.repository.PosSessionRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the ADR-0044 D-3a age-restriction gate in {@link PosSaleServiceImpl#processSale}.
 *
 * <p>All repository/service collaborators are mocked so the gate logic can be exercised in
 * isolation without a database or Spring context.
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>Restricted product + no ageVerified + no override → 409 ConflictException.</li>
 *   <li>Restricted product + ageVerified=true → proceeds past the gate (no exception).</li>
 *   <li>Restricted product + no ageVerified + caller holds POS.SALE.AGE_OVERRIDE → proceeds.</li>
 *   <li>NONE (unrestricted) product → no gate interaction, proceeds normally.</li>
 * </ol>
 */
class PosSaleServiceImplTest {

    private static final Long   COMPANY_ID  = 1L;
    private static final Long   BRANCH_ID   = 2L;
    private static final Long   USER_ID     = 10L;
    private static final Long   CUSTOMER_ID = 20L;
    private static final Long   PRODUCT_ID  = 30L;
    private static final Long   UNIT_ID     = 40L;
    private static final String SESSION_UID = "SESSION-001";

    private PosSessionRepository        posSessionRepo;
    private SalesInvoiceRepository      invoiceRepo;
    private SalesInvoiceService         invoiceService;
    private ProductRepository           products;
    private UnitOfMeasureRepository     units;
    private CustomerRepository          customers;
    private CompanyRepository           companies;
    private ScopeGuard                  scopeGuard;
    private AuditService                audit;
    private PosSaleIdempotencyRepository idempotency;
    private PermissionResolver          permissionResolver;

    private PosSaleServiceImpl service;

    @BeforeEach
    void setUp() {
        posSessionRepo     = mock(PosSessionRepository.class);
        invoiceRepo        = mock(SalesInvoiceRepository.class);
        invoiceService     = mock(SalesInvoiceService.class);
        products           = mock(ProductRepository.class);
        units              = mock(UnitOfMeasureRepository.class);
        customers          = mock(CustomerRepository.class);
        companies          = mock(CompanyRepository.class);
        scopeGuard         = mock(ScopeGuard.class);
        audit              = mock(AuditService.class);
        idempotency        = mock(PosSaleIdempotencyRepository.class);
        permissionResolver = mock(PermissionResolver.class);

        service = new PosSaleServiceImpl(
                posSessionRepo, invoiceRepo, invoiceService, products, units,
                customers, companies, scopeGuard, audit, idempotency, permissionResolver);

        // Common stubs: open session in company scope
        PosSession session = buildSession();
        when(posSessionRepo.findByUid(SESSION_UID)).thenReturn(Optional.of(session));

        Customer customer = mock(Customer.class);
        when(customer.getUid()).thenReturn("CUST-001");
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));

        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        when(company.getUid()).thenReturn("CO-001");
        when(companies.findById(COMPANY_ID)).thenReturn(Optional.of(company));

        // idempotency: always returns 1 (key claimed, not a duplicate)
        when(idempotency.tryReserve(any(), any())).thenReturn(1);

        // invoice service stubs — minimal happy path through the non-gate steps
        SalesInvoiceDto draftDto = mock(SalesInvoiceDto.class);
        when(draftDto.uid()).thenReturn("INV-001");
        when(invoiceService.create(any())).thenReturn(draftDto);

        com.erp.modules.sales.domain.entity.SalesInvoice invoice =
                mock(com.erp.modules.sales.domain.entity.SalesInvoice.class);
        when(invoice.getGrossTotalAmount()).thenReturn(BigDecimal.TEN);
        when(invoiceRepo.findByUid("INV-001")).thenReturn(Optional.of(invoice));
        when(invoiceRepo.saveAndFlush(any())).thenReturn(invoice);

        SalesInvoiceDto finalDto = mock(SalesInvoiceDto.class);
        when(invoiceService.getByUid("INV-001")).thenReturn(finalDto);

        UnitOfMeasure unit = mock(UnitOfMeasure.class);
        when(unit.getUid()).thenReturn("UOM-001");
        when(units.findById(UNIT_ID)).thenReturn(Optional.of(unit));

        // default: no POS.SALE.AGE_OVERRIDE
        when(permissionResolver.hasPermission(any(), eq("POS.SALE.AGE_OVERRIDE"), any(long.class)))
                .thenReturn(false);

        // Set a non-root principal in RequestContext
        RequestContext.set(new RequestContext.Principal(
                USER_ID, "cashier", false, COMPANY_ID, BRANCH_ID, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: restricted product + no ack + no override → 409
    // =========================================================================

    @Test
    void processSale_restrictedProduct_noAck_noOverride_throws409() {
        Product p = product(RestrictedKind.AGE_18);    // build before entering when() chain
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        PosSaleRequest req = request(null);   // ageVerified omitted (null)

        assertThatThrownBy(() -> service.processSale(null, req))
                .as("AGE_18 product without ack or override must be blocked with 409")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Age-restricted")
                .hasMessageContaining("ageVerified=true");
    }

    @Test
    void processSale_restrictedProduct_ageVerifiedFalse_noOverride_throws409() {
        Product p = product(RestrictedKind.AGE_21);
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        PosSaleRequest req = request(false);  // explicit false

        assertThatThrownBy(() -> service.processSale(null, req))
                .as("AGE_21 product with ageVerified=false must be blocked")
                .isInstanceOf(ConflictException.class);
    }

    // =========================================================================
    // Bar 2: restricted product + ageVerified=true → proceeds
    // =========================================================================

    @Test
    void processSale_restrictedProduct_ageVerifiedTrue_proceeds() {
        Product p = product(RestrictedKind.AGE_18);
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        PosSaleRequest req = request(true);

        assertThatCode(() -> service.processSale(null, req))
                .as("AGE_18 product with ageVerified=true must not throw")
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // Bar 3: restricted product + no ack + caller holds POS.SALE.AGE_OVERRIDE → proceeds
    // =========================================================================

    @Test
    void processSale_restrictedProduct_noAck_callerHasOverride_proceeds() {
        when(permissionResolver.hasPermission(any(), eq("POS.SALE.AGE_OVERRIDE"), any(long.class)))
                .thenReturn(true);
        Product p = product(RestrictedKind.AGE_18);    // build after re-stubbing permissionResolver
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        PosSaleRequest req = request(null);   // no ack, but override held

        assertThatCode(() -> service.processSale(null, req))
                .as("AGE_18 product with POS.SALE.AGE_OVERRIDE must not throw even without ack")
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // Bar 4: NONE product → gate is inert (no exception regardless of ageVerified)
    // =========================================================================

    @Test
    void processSale_noneProduct_noAck_noOverride_proceeds() {
        Product p = product(RestrictedKind.NONE);
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        PosSaleRequest req = request(null);   // no ack needed

        assertThatCode(() -> service.processSale(null, req))
                .as("NONE (unrestricted) product must never be gated")
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // FIX 2 (busy-day sim, CRITICAL): POS reverse/void must not route through the AR-oriented
    // settled-tender guard — every POS sale is paid at the till, so that guard made
    // POS.SALE.VOID permanently unsatisfiable (always 409).
    // =========================================================================

    @Test
    void reverseSale_posOriginOpenSession_callsVoidPosInvoice_notVoidInvoice() {
        SalesInvoice inv = mock(SalesInvoice.class);
        when(inv.getCompanyId()).thenReturn(COMPANY_ID);
        when(inv.getOrigin()).thenReturn(DocumentOrigin.POS);
        when(inv.getPosSessionId()).thenReturn(99L);
        when(inv.getId()).thenReturn(500L);
        when(inv.getUid()).thenReturn("INV-999");
        when(invoiceRepo.findByUid("INV-999")).thenReturn(Optional.of(inv));
        // buildSession() (setUp's stubbed OPEN session) has id=99L — matches inv.getPosSessionId()
        PosSession openSession = buildSession();
        when(posSessionRepo.findById(99L)).thenReturn(Optional.of(openSession));

        service.reverseSale("INV-999", "cashier rang the wrong item");

        verify(invoiceService).voidPosInvoice(eq("INV-999"), any(VoidInvoiceRequest.class));
        verify(invoiceService, never()).voidInvoice(any(), any());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PosSaleRequest request(Boolean ageVerified) {
        return new PosSaleRequest(
                SESSION_UID, CUSTOMER_ID, null, "TZS",
                List.of(new PosSaleRequest.LineItem(
                        PRODUCT_ID, UNIT_ID, BigDecimal.ONE, null, null)),
                null, BigDecimal.TEN, null, ageVerified);
    }

    private Product product(RestrictedKind kind) {
        // Build a real Product — no mock() calls inside when(...) chains (Mockito strict rule).
        // UnitOfMeasure is pre-stubbed in setUp so getUid() is safe.
        UnitOfMeasure baseUnit = stubbedUnit();
        Product p = new Product(COMPANY_ID, "P001", "Beer 500ml", ProductType.GOODS,
                true, true, baseUnit, USER_ID);
        p.setRestrictedKind(kind);
        return p;
    }

    /** Returns a pre-stubbed UnitOfMeasure mock that is safe to use outside a when() chain. */
    private UnitOfMeasure stubbedUnit() {
        UnitOfMeasure u = mock(UnitOfMeasure.class);
        when(u.getUid()).thenReturn("UOM-001");
        return u;
    }

    private PosSession buildSession() {
        PosSession s = mock(PosSession.class);
        when(s.getCompanyId()).thenReturn(COMPANY_ID);
        when(s.getBranchId()).thenReturn(BRANCH_ID);
        when(s.getId()).thenReturn(99L);
        when(s.getUid()).thenReturn(SESSION_UID);
        when(s.getStatus()).thenReturn(PosSessionStatus.OPEN);
        return s;
    }
}
