package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Supplier;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.purchases.domain.dto.ConvertRequisitionRequest;
import com.erp.modules.purchases.domain.dto.CreateRfqRequest;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.dto.RfqDto;
import com.erp.modules.purchases.domain.entity.PurchaseRequisition;
import com.erp.modules.purchases.domain.entity.PurchaseRequisitionLine;
import com.erp.modules.purchases.domain.enums.PurchaseOrderStatus;
import com.erp.modules.purchases.domain.enums.RequisitionStatus;
import com.erp.modules.purchases.domain.enums.RfqStatus;
import com.erp.modules.purchases.repository.PurchaseRequisitionLineRepository;
import com.erp.modules.purchases.repository.PurchaseRequisitionRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link PurchaseRequisitionServiceImpl#convert} (D-3 — Convert actually creates
 * the target document instead of leaving {@code convertedToUid} unset).
 *
 * <p>{@link RfqService} and {@link PurchaseOrderService} are mocked — persistence of the created
 * RFQ/PO themselves is covered by their own service tests; this test asserts that the requisition
 * service (a) builds the correct request payloads from its own lines, (b) links
 * {@code convertedToUid}/{@code convertedToType} to whatever the collaborator returns, and
 * (c) enforces the pre-conditions (APPROVED status, supplier(s) present).
 */
class PurchaseRequisitionServiceImplTest {

    private static final Long COMPANY_ID = 10L;
    private static final Long BRANCH_ID  = 20L;

    private PurchaseRequisitionRepository     requisitions;
    private PurchaseRequisitionLineRepository reqLines;
    private CompanyRepository                 companies;
    private ProductRepository                 products;
    private UnitOfMeasureRepository           units;
    private SupplierRepository                suppliers;
    private PurchaseNumberGenerator           numberGen;
    private ScopeGuard                        scopeGuard;
    private AuditService                      audit;
    private RfqService                        rfqService;
    private PurchaseOrderService              purchaseOrderService;

    private PurchaseRequisitionServiceImpl service;

    @BeforeEach
    void setUp() {
        requisitions         = mock(PurchaseRequisitionRepository.class);
        reqLines             = mock(PurchaseRequisitionLineRepository.class);
        companies            = mock(CompanyRepository.class);
        products             = mock(ProductRepository.class);
        units                = mock(UnitOfMeasureRepository.class);
        suppliers            = mock(SupplierRepository.class);
        numberGen            = mock(PurchaseNumberGenerator.class);
        scopeGuard           = mock(ScopeGuard.class);
        audit                = mock(AuditService.class);
        rfqService           = mock(RfqService.class);
        purchaseOrderService = mock(PurchaseOrderService.class);

        service = new PurchaseRequisitionServiceImpl(
                requisitions, reqLines, companies, products, units, suppliers, numberGen,
                scopeGuard, audit, rfqService, purchaseOrderService);

        RequestContext.set(new RequestContext.Principal(
                1L, "buyer@test.com", false, COMPANY_ID, BRANCH_ID, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // RFQ conversion
    // -------------------------------------------------------------------------

    @Test
    void convert_rfq_createsRfqFromOwnLinesAndSuppliers_setsConvertedToUidAndType() {
        PurchaseRequisition r = approvedRequisition("REQ-UID-1");
        PurchaseRequisitionLine line = requisitionLine(500L, 700L, new BigDecimal("4"));
        when(reqLines.findByPurchaseRequisitionIdOrderByLineNo(1L)).thenReturn(List.of(line));

        Company company = mock(Company.class);
        when(company.getUid()).thenReturn("COMPANY-UID-1");
        when(companies.findScopedById(COMPANY_ID)).thenReturn(Optional.of(company));

        activeSupplier("SUP-UID-1");
        activeSupplier("SUP-UID-2");

        RfqDto createdRfq = rfqDto("RFQ-UID-99");
        when(rfqService.create(any(CreateRfqRequest.class))).thenReturn(createdRfq);

        ConvertRequisitionRequest req = new ConvertRequisitionRequest(
                "RFQ", List.of("SUP-UID-1", "SUP-UID-2"), null, null);

        String createdUid = service.convert("REQ-UID-1", req);

        assertThat(createdUid).isEqualTo("RFQ-UID-99");
        verify(r).setConvertedToUid("RFQ-UID-99");
        verify(r).setConvertedToType("RFQ");
        verify(r).setStatus(RequisitionStatus.CONVERTED);

        ArgumentCaptor<CreateRfqRequest> captor = ArgumentCaptor.forClass(CreateRfqRequest.class);
        verify(rfqService).create(captor.capture());
        CreateRfqRequest built = captor.getValue();
        assertThat(built.companyUid()).isEqualTo("COMPANY-UID-1");
        assertThat(built.sourceRequisitionUid()).isEqualTo("REQ-UID-1");
        assertThat(built.supplierUids()).containsExactly("SUP-UID-1", "SUP-UID-2");
        assertThat(built.lines()).hasSize(1);
        assertThat(built.lines().get(0).productId()).isEqualTo(500L);
        assertThat(built.lines().get(0).unitId()).isEqualTo(700L);
        assertThat(built.lines().get(0).quantity()).isEqualByComparingTo(new BigDecimal("4"));

        verify(purchaseOrderService, never()).createFromRequisition(any(), any(), any());
    }

    // FIX E — RFQ convert must reject bad suppliers (unknown/foreign-company/ARCHIVED), mirroring
    // the PO branch's resolveSupplier instead of silently passing them through to RfqService.

    @Test
    void convert_rfq_unknownSupplier_rejected() {
        approvedRequisition("REQ-UID-1U");
        Company company = mock(Company.class);
        when(company.getUid()).thenReturn("COMPANY-UID-1");
        when(companies.findScopedById(COMPANY_ID)).thenReturn(Optional.of(company));
        when(suppliers.findByCompanyIdAndUid(COMPANY_ID, "SUP-UID-UNKNOWN"))
                .thenReturn(Optional.empty());

        ConvertRequisitionRequest req = new ConvertRequisitionRequest(
                "RFQ", List.of("SUP-UID-UNKNOWN"), null, null);

        assertThatThrownBy(() -> service.convert("REQ-UID-1U", req))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Supplier");

        verify(rfqService, never()).create(any());
    }

    @Test
    void convert_rfq_archivedSupplier_rejected() {
        approvedRequisition("REQ-UID-1A");
        Company company = mock(Company.class);
        when(company.getUid()).thenReturn("COMPANY-UID-1");
        when(companies.findScopedById(COMPANY_ID)).thenReturn(Optional.of(company));
        Supplier archived = mock(Supplier.class);
        when(archived.getStatus()).thenReturn(MasterStatus.ARCHIVED);
        when(suppliers.findByCompanyIdAndUid(COMPANY_ID, "SUP-UID-ARCHIVED"))
                .thenReturn(Optional.of(archived));

        ConvertRequisitionRequest req = new ConvertRequisitionRequest(
                "RFQ", List.of("SUP-UID-ARCHIVED"), null, null);

        assertThatThrownBy(() -> service.convert("REQ-UID-1A", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("archived");

        verify(rfqService, never()).create(any());
    }

    @Test
    void convert_rfq_noSuppliers_rejected() {
        approvedRequisition("REQ-UID-2");
        ConvertRequisitionRequest req = new ConvertRequisitionRequest("RFQ", List.of(), null, null);

        assertThatThrownBy(() -> service.convert("REQ-UID-2", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supplier");

        verify(rfqService, never()).create(any());
    }

    @Test
    void convert_rfq_nullSuppliers_rejected() {
        approvedRequisition("REQ-UID-2B");
        ConvertRequisitionRequest req = new ConvertRequisitionRequest("RFQ", null, null, null);

        assertThatThrownBy(() -> service.convert("REQ-UID-2B", req))
                .isInstanceOf(IllegalArgumentException.class);

        verify(rfqService, never()).create(any());
    }

    // -------------------------------------------------------------------------
    // PO conversion
    // -------------------------------------------------------------------------

    @Test
    void convert_purchaseOrder_createsPoFromRequisition_setsConvertedToUidAndType() {
        PurchaseRequisition r = approvedRequisition("REQ-UID-3");

        PurchaseOrderDto createdPo = purchaseOrderDto("PO-UID-77");
        when(purchaseOrderService.createFromRequisition("REQ-UID-3", "SUP-UID-9", "TZS"))
                .thenReturn(createdPo);

        ConvertRequisitionRequest req = new ConvertRequisitionRequest(
                "PURCHASE_ORDER", null, "SUP-UID-9", "TZS");

        String createdUid = service.convert("REQ-UID-3", req);

        assertThat(createdUid).isEqualTo("PO-UID-77");
        verify(r).setConvertedToUid("PO-UID-77");
        verify(r).setConvertedToType("PURCHASE_ORDER");
        verify(r).setStatus(RequisitionStatus.CONVERTED);
        verify(purchaseOrderService).createFromRequisition("REQ-UID-3", "SUP-UID-9", "TZS");
        verify(rfqService, never()).create(any());
    }

    @Test
    void convert_purchaseOrder_noSupplier_rejected() {
        approvedRequisition("REQ-UID-4");
        ConvertRequisitionRequest req = new ConvertRequisitionRequest(
                "PURCHASE_ORDER", null, null, null);

        assertThatThrownBy(() -> service.convert("REQ-UID-4", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supplier");

        verify(purchaseOrderService, never()).createFromRequisition(any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Status guard
    // -------------------------------------------------------------------------

    @Test
    void convert_notApproved_rejected() {
        PurchaseRequisition r = mock(PurchaseRequisition.class);
        when(r.getId()).thenReturn(1L);
        when(r.getUid()).thenReturn("REQ-UID-5");
        when(r.getCompanyId()).thenReturn(COMPANY_ID);
        when(r.getStatus()).thenReturn(RequisitionStatus.SUBMITTED);
        when(requisitions.findByUid("REQ-UID-5")).thenReturn(Optional.of(r));

        ConvertRequisitionRequest req = new ConvertRequisitionRequest(
                "PURCHASE_ORDER", null, "SUP-UID-1", null);

        assertThatThrownBy(() -> service.convert("REQ-UID-5", req))
                .isInstanceOf(IllegalStateException.class);

        verify(purchaseOrderService, never()).createFromRequisition(any(), any(), any());
        verify(rfqService, never()).create(any());
    }

    @Test
    void convert_invalidTargetType_rejected() {
        approvedRequisition("REQ-UID-6");
        ConvertRequisitionRequest req = new ConvertRequisitionRequest(
                "NOT_A_TYPE", null, "SUP-UID-1", null);

        assertThatThrownBy(() -> service.convert("REQ-UID-6", req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PurchaseRequisition approvedRequisition(String uid) {
        PurchaseRequisition r = mock(PurchaseRequisition.class);
        when(r.getId()).thenReturn(1L);
        when(r.getUid()).thenReturn(uid);
        when(r.getCompanyId()).thenReturn(COMPANY_ID);
        when(r.getBranchId()).thenReturn(BRANCH_ID);
        when(r.getStatus()).thenReturn(RequisitionStatus.APPROVED);
        when(r.getNotes()).thenReturn("Restock");
        when(requisitions.findByUid(uid)).thenReturn(Optional.of(r));
        return r;
    }

    private Supplier activeSupplier(String uid) {
        Supplier s = mock(Supplier.class);
        when(s.getStatus()).thenReturn(MasterStatus.ACTIVE);
        when(suppliers.findByCompanyIdAndUid(COMPANY_ID, uid)).thenReturn(Optional.of(s));
        return s;
    }

    private PurchaseRequisitionLine requisitionLine(Long productId, Long unitId, BigDecimal qty) {
        PurchaseRequisitionLine l = mock(PurchaseRequisitionLine.class);
        when(l.getProductId()).thenReturn(productId);
        when(l.getUnitId()).thenReturn(unitId);
        when(l.getRequestedQty()).thenReturn(qty);
        return l;
    }

    private RfqDto rfqDto(String uid) {
        return new RfqDto(
                99L, uid, COMPANY_ID, BRANCH_ID, null, RfqStatus.DRAFT,
                "REQ-SRC", null, null, null, null, null,
                null, null, null, null,
                List.of(), List.of());
    }

    private PurchaseOrderDto purchaseOrderDto(String uid) {
        return new PurchaseOrderDto(
                77L, uid, COMPANY_ID, BRANCH_ID, null, PurchaseOrderStatus.DRAFT,
                1L, "SUP-01", "Acme", "TZS", BigDecimal.ZERO,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, List.of());
    }
}
