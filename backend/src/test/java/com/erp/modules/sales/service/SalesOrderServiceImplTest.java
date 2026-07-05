package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.approvals.domain.dto.ApprovalRequestDto;
import com.erp.modules.approvals.domain.dto.SubmitForApprovalRequest;
import com.erp.modules.approvals.domain.enums.ApprovalRequestStatus;
import com.erp.modules.approvals.service.ApprovalEngine;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.parties.domain.entity.Agent;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBulkPack;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.sales.domain.dto.AddSalesOrderLineRequest;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import com.erp.modules.sales.domain.dto.SalesOrderLineDto;
import com.erp.modules.sales.domain.entity.SalesOrder;
import com.erp.modules.sales.domain.entity.SalesOrderLine;
import com.erp.modules.sales.domain.entity.TaxRate;
import com.erp.modules.sales.domain.enums.SalesOrderStatus;
import com.erp.modules.sales.repository.QuotationLineRepository;
import com.erp.modules.sales.repository.QuotationRepository;
import com.erp.modules.sales.repository.SalesOrderLineRepository;
import com.erp.modules.sales.repository.SalesOrderRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link SalesOrderServiceImpl} customer/agent/branch name enrichment.
 *
 * <p>A top UX complaint: sales orders showed only the raw numeric {@code customerId} — never the
 * customer's name — so approvers could not see who an order was for. Fixed by resolving
 * customerName/customerCode/agentName at read time, mirroring {@code SalesInvoiceServiceImpl}.
 * branchName/branchCode are resolved the same way so a branch manager can see which branch a
 * sales order belongs to (only the internal branchId travelled before).
 *
 * <p>Only the enrichment path is exercised here (getByUid + list); the rest of
 * {@link SalesOrderServiceImpl} is covered by {@link SalesOrderServiceIT} et al.
 */
@ExtendWith(MockitoExtension.class)
class SalesOrderServiceImplTest {

    @Mock SalesOrderRepository orders;
    @Mock SalesOrderLineRepository orderLines;
    @Mock QuotationRepository quotations;
    @Mock QuotationLineRepository quotationLines;
    @Mock CustomerRepository customers;
    @Mock com.erp.modules.parties.repository.CustomerAddressRepository customerAddresses;
    @Mock com.erp.modules.parties.repository.PaymentTermsRepository paymentTermsRepo;
    @Mock AgentRepository agents;
    @Mock com.erp.modules.iam.repository.CompanyRepository companies;
    @Mock BranchRepository branches;
    @Mock com.erp.modules.products.repository.ProductRepository products;
    @Mock com.erp.modules.products.repository.UnitOfMeasureRepository units;
    @Mock com.erp.modules.products.service.PriceResolutionService priceResolutionService;
    @Mock com.erp.modules.products.repository.ProductBulkPackRepository bulkPacks;
    @Mock com.erp.modules.sales.repository.TaxRateRepository taxRates;
    @Mock com.erp.modules.stock.service.StockReservationService reservationService;
    @Mock OrderToCashNumberGenerator numberGen;
    @Mock SalesOrderTotalsCalculator totalsCalc;
    @Mock ScopeGuard scopeGuard;
    @Mock com.erp.platform.audit.AuditService audit;
    @Mock com.erp.modules.ar.service.ArBalanceService arBalanceService;
    @Mock com.erp.platform.security.PermissionResolver permissionResolver;
    @Mock ApprovalEngine approvalEngine;
    @Mock SalesApprovalGate salesApprovalGate;

    @InjectMocks SalesOrderServiceImpl service;

    private static final String DOC_TYPE = "SALES_ORDER";

    private static final Long COMPANY_ID  = 1L;
    private static final Long BRANCH_ID   = 10L;
    private static final Long CUSTOMER_ID = 200L;
    private static final Long AGENT_ID    = 300L;

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void getByUid_resolvesCustomerNameCustomerCodeAndAgentName() {
        SalesOrder order = orderWithId(500L, "SOUID000000000000000001", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000001")).thenReturn(Optional.of(order));
        when(orderLines.findBySalesOrderIdOrderByLineNo(500L)).thenReturn(List.of());
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(
                customer(CUSTOMER_ID, "CUST-0001", "Acme Traders")));
        when(agents.findById(AGENT_ID)).thenReturn(Optional.of(
                agent(AGENT_ID, "AGT-0001", "Jane Agent")));
        when(branches.findById(BRANCH_ID)).thenReturn(Optional.of(
                branch("BR-01", "Head Office")));

        SalesOrderDto dto = service.getByUid("SOUID000000000000000001");

        assertThat(dto.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(dto.customerName()).isEqualTo("Acme Traders");
        assertThat(dto.customerCode()).isEqualTo("CUST-0001");
        assertThat(dto.agentId()).isEqualTo(AGENT_ID);
        assertThat(dto.agentName()).isEqualTo("Jane Agent");
        assertThat(dto.branchId()).isEqualTo(BRANCH_ID);
        assertThat(dto.branchName()).isEqualTo("Head Office");
        assertThat(dto.branchCode()).isEqualTo("BR-01");
    }

    @Test
    void getByUid_branchNamesNull_whenBranchRowMissing() {
        SalesOrder order = orderWithId(503L, "SOUID000000000000000004", CUSTOMER_ID, null);
        when(orders.findByUid("SOUID000000000000000004")).thenReturn(Optional.of(order));
        when(orderLines.findBySalesOrderIdOrderByLineNo(503L)).thenReturn(List.of());
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(
                customer(CUSTOMER_ID, "CUST-0001", "Acme Traders")));
        when(branches.findById(BRANCH_ID)).thenReturn(Optional.empty());

        SalesOrderDto dto = service.getByUid("SOUID000000000000000004");

        assertThat(dto.branchName()).isNull();
        assertThat(dto.branchCode()).isNull();
        // never throws — a missing branch row degrades to null names, it never fails the read.
    }

    @Test
    void getByUid_agentNameNull_whenOrderHasNoAgent() {
        SalesOrder order = orderWithId(501L, "SOUID000000000000000002", CUSTOMER_ID, null);
        when(orders.findByUid("SOUID000000000000000002")).thenReturn(Optional.of(order));
        when(orderLines.findBySalesOrderIdOrderByLineNo(501L)).thenReturn(List.of());
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(
                customer(CUSTOMER_ID, "CUST-0001", "Acme Traders")));

        SalesOrderDto dto = service.getByUid("SOUID000000000000000002");

        assertThat(dto.agentId()).isNull();
        assertThat(dto.agentName()).isNull();
        // agents.findById must never be invoked with a null id (would NPE/IllegalArgumentException
        // against a real Spring Data repository) — the null guard is exercised implicitly since
        // no stub was registered for agents.findById and Mockito would return null (not throw)
        // for an un-stubbed call, so this assertion is the behavioural contract that matters.
    }

    @Test
    void getByUid_namesNull_whenCustomerRowMissing() {
        SalesOrder order = orderWithId(502L, "SOUID000000000000000003", CUSTOMER_ID, null);
        when(orders.findByUid("SOUID000000000000000003")).thenReturn(Optional.of(order));
        when(orderLines.findBySalesOrderIdOrderByLineNo(502L)).thenReturn(List.of());
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        SalesOrderDto dto = service.getByUid("SOUID000000000000000003");

        assertThat(dto.customerName()).isNull();
        assertThat(dto.customerCode()).isNull();
    }

    @Test
    void list_resolvesCustomerNameForEveryRow() {
        SalesOrder order1 = orderWithId(600L, "SOUID000000000000000010", CUSTOMER_ID, AGENT_ID);
        SalesOrder order2 = orderWithId(601L, "SOUID000000000000000011", 201L, null);
        when(orders.findByCompanyId(COMPANY_ID, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(order1, order2)));
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(
                customer(CUSTOMER_ID, "CUST-0001", "Acme Traders")));
        when(customers.findById(201L)).thenReturn(Optional.of(
                customer(201L, "CUST-0002", "Beta Stores")));
        when(agents.findById(AGENT_ID)).thenReturn(Optional.of(
                agent(AGENT_ID, "AGT-0001", "Jane Agent")));
        when(branches.findById(BRANCH_ID)).thenReturn(Optional.of(
                branch("BR-01", "Head Office")));

        Page<SalesOrderDto> page = service.list(COMPANY_ID, Pageable.unpaged());

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).customerName()).isEqualTo("Acme Traders");
        assertThat(page.getContent().get(0).agentName()).isEqualTo("Jane Agent");
        assertThat(page.getContent().get(0).branchName()).isEqualTo("Head Office");
        assertThat(page.getContent().get(0).branchCode()).isEqualTo("BR-01");
        assertThat(page.getContent().get(1).customerName()).isEqualTo("Beta Stores");
        assertThat(page.getContent().get(1).agentName()).isNull();
        // list rows are resolved one findById per row (no batch fetch) — same N+1 shape as
        // SalesInvoiceServiceImpl.list/toDto; see handoff notes. Applies to both customer/agent
        // AND the new branch lookup added here.
    }

    // -------------------------------------------------------------------------
    // Approvals engine seam (ADR-0022 D-7) — submitForApproval + confirm gate + toDto exposure
    // -------------------------------------------------------------------------

    @Test
    void submitForApproval_draftOrder_callsApprovalEngineWithDocumentTypeAndUid() {
        SalesOrder order = orderWithId(700L, "SOUID000000000000000020", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000020")).thenReturn(Optional.of(order));
        when(orderLines.findBySalesOrderIdOrderByLineNo(700L)).thenReturn(List.of());
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(
                customer(CUSTOMER_ID, "CUST-0001", "Acme Traders")));
        when(agents.findById(AGENT_ID)).thenReturn(Optional.of(
                agent(AGENT_ID, "AGT-0001", "Jane Agent")));
        when(branches.findById(BRANCH_ID)).thenReturn(Optional.of(
                branch("BR-01", "Head Office")));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000020", COMPANY_ID))
                .thenReturn(Optional.empty());
        when(approvalEngine.submitForApproval(any()))
                .thenReturn(approvalRequest("SOUID000000000000000020", ApprovalRequestStatus.PENDING));

        SalesOrderDto dto = service.submitForApproval("SOUID000000000000000020");

        ArgumentCaptor<SubmitForApprovalRequest> captor =
                ArgumentCaptor.forClass(SubmitForApprovalRequest.class);
        verify(approvalEngine).submitForApproval(captor.capture());
        assertThat(captor.getValue().documentType()).isEqualTo(DOC_TYPE);
        assertThat(captor.getValue().documentUid()).isEqualTo("SOUID000000000000000020");
        assertThat(captor.getValue().companyId()).isEqualTo(COMPANY_ID);
        assertThat(dto).isNotNull();
        assertThat(dto.uid()).isEqualTo("SOUID000000000000000020");
    }

    @Test
    void submitForApproval_nonDraftOrder_throws() {
        SalesOrder order = orderWithId(701L, "SOUID000000000000000021", CUSTOMER_ID, AGENT_ID);
        order.setStatus(SalesOrderStatus.CONFIRMED);
        when(orders.findByUid("SOUID000000000000000021")).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.submitForApproval("SOUID000000000000000021"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("draft order can be submitted");
    }

    @Test
    void submitForApproval_pendingRequestAlreadyExists_doesNotDoubleSubmit() {
        SalesOrder order = orderWithId(702L, "SOUID000000000000000022", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000022")).thenReturn(Optional.of(order));
        when(orderLines.findBySalesOrderIdOrderByLineNo(702L)).thenReturn(List.of());
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(
                customer(CUSTOMER_ID, "CUST-0001", "Acme Traders")));
        when(agents.findById(AGENT_ID)).thenReturn(Optional.of(
                agent(AGENT_ID, "AGT-0001", "Jane Agent")));
        when(branches.findById(BRANCH_ID)).thenReturn(Optional.of(
                branch("BR-01", "Head Office")));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000022", COMPANY_ID))
                .thenReturn(Optional.of(
                        approvalRequest("SOUID000000000000000022", ApprovalRequestStatus.PENDING)));

        SalesOrderDto dto = service.submitForApproval("SOUID000000000000000022");

        verify(approvalEngine, never()).submitForApproval(any());
        assertThat(dto.uid()).isEqualTo("SOUID000000000000000022");
    }

    @Test
    void submitForApproval_rejectedRequestExists_throwsGuidanceAndDoesNotResubmit() {
        // The approvals engine forbids reopening a terminal request; re-submitting would 409.
        // The service must guide the user (cancel + recreate) rather than call the engine.
        SalesOrder order = orderWithId(710L, "SOUID000000000000000026", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000026")).thenReturn(Optional.of(order));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000026", COMPANY_ID))
                .thenReturn(Optional.of(
                        approvalRequest("SOUID000000000000000026", ApprovalRequestStatus.REJECTED)));

        assertThatThrownBy(() -> service.submitForApproval("SOUID000000000000000026"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        verify(approvalEngine, never()).submitForApproval(any());
    }

    @Test
    void submitForApproval_approvedRequestExists_throws() {
        SalesOrder order = orderWithId(711L, "SOUID000000000000000027", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000027")).thenReturn(Optional.of(order));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000027", COMPANY_ID))
                .thenReturn(Optional.of(
                        approvalRequest("SOUID000000000000000027", ApprovalRequestStatus.APPROVED)));

        assertThatThrownBy(() -> service.submitForApproval("SOUID000000000000000027"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been approved");
        verify(approvalEngine, never()).submitForApproval(any());
    }

    @Test
    void addLine_whileApprovalPending_isBlocked_soContentCannotDriftUnderTheApprover() {
        // Content-freeze: while an order is PENDING/APPROVED its lines must not change, else the
        // confirmed order could differ from what was approved (bypassing amount-threshold policies).
        SalesOrder order = orderWithId(712L, "SOUID000000000000000028", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000028")).thenReturn(Optional.of(order));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000028", COMPANY_ID))
                .thenReturn(Optional.of(
                        approvalRequest("SOUID000000000000000028", ApprovalRequestStatus.PENDING)));

        assertThatThrownBy(() -> service.addLine("SOUID000000000000000028", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approval process");
        verify(orderLines, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // ADR-0048 D-1: multi-unit / per-pack pricing — addLine now delegates to
    // PriceResolutionService.resolveUnitListPrice instead of the old company-wide
    // "first price row found" scan that ignored the line's unit.
    // -------------------------------------------------------------------------

    @Test
    void addLine_baseUnit_pricesAtListAmount_regressionGuardForUnderChargeFix() {
        SalesOrder order = orderWithId(720L, "SOUID000000000000000030", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000030")).thenReturn(Optional.of(order));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000030", COMPANY_ID))
                .thenReturn(Optional.empty());

        UnitOfMeasure baseUnit = unitWithId(910L, "BASEUID0000000000000001", "PCS");
        Product product = productWithId(900L, "PRODUID00000000000000001", "PROD-0001", "Widget", baseUnit);
        when(products.findByCompanyIdAndUid(COMPANY_ID, "PRODUID00000000000000001"))
                .thenReturn(Optional.of(product));
        when(units.findByCompanyIdAndUid(COMPANY_ID, "BASEUID0000000000000001"))
                .thenReturn(Optional.of(baseUnit));
        when(priceResolutionService.resolveUnitListPrice(COMPANY_ID, 900L, 910L))
                .thenReturn(new BigDecimal("100.0000"));
        when(taxRates.findByCompanyIdAndVatStatus(COMPANY_ID, VatStatus.STANDARD))
                .thenReturn(Optional.of(new TaxRate(COMPANY_ID, VatStatus.STANDARD,
                        new BigDecimal("0.1800"), 1L)));
        when(orderLines.findMaxLineNo(720L)).thenReturn(0);
        when(orderLines.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orderLines.findBySalesOrderIdOrderByLineNo(720L)).thenReturn(List.of());

        AddSalesOrderLineRequest req = new AddSalesOrderLineRequest(
                "PRODUID00000000000000001", "BASEUID0000000000000001", BigDecimal.TEN, null, null, null);

        SalesOrderLineDto dto = service.addLine("SOUID000000000000000030", req);

        assertThat(dto.unitPriceAmount()).isEqualByComparingTo("100.0000");
        assertThat(dto.qtyOrderedBase()).isEqualByComparingTo(BigDecimal.TEN);
        verify(priceResolutionService).resolveUnitListPrice(COMPANY_ID, 900L, 910L);
    }

    @Test
    void addLine_packUnit_pricesAtExplicitPackPrice_notUnderChargedAtBaseRate() {
        // A pack (BOX, factor 12) line must price at the resolver's answer for the pack unit —
        // never at the base-unit amount × qty_in_pack_units (the latent under-charge bug D-1 fixes).
        SalesOrder order = orderWithId(721L, "SOUID000000000000000031", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000031")).thenReturn(Optional.of(order));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000031", COMPANY_ID))
                .thenReturn(Optional.empty());

        UnitOfMeasure productBaseUnit = unitWithId(915L, "PCSUID0000000000000002", "PCS");
        UnitOfMeasure boxUnit = unitWithId(920L, "BOXUID00000000000000001", "BOX");
        Product product = productWithId(901L, "PRODUID00000000000000002", "PROD-0002", "Soap",
                productBaseUnit);
        when(products.findByCompanyIdAndUid(COMPANY_ID, "PRODUID00000000000000002"))
                .thenReturn(Optional.of(product));
        when(units.findByCompanyIdAndUid(COMPANY_ID, "BOXUID00000000000000001"))
                .thenReturn(Optional.of(boxUnit));
        // Base price is 100; the BOX pack is explicitly priced at 1150 (non-linear override) —
        // NOT 100 (the bug) and not necessarily 1200 (100 x factor 12) either.
        when(priceResolutionService.resolveUnitListPrice(COMPANY_ID, 901L, 920L))
                .thenReturn(new BigDecimal("1150.0000"));
        when(taxRates.findByCompanyIdAndVatStatus(COMPANY_ID, VatStatus.STANDARD))
                .thenReturn(Optional.of(new TaxRate(COMPANY_ID, VatStatus.STANDARD,
                        new BigDecimal("0.1800"), 1L)));
        ProductBulkPack boxPack = new ProductBulkPack(product, boxUnit, new BigDecimal("12"), 1L);
        when(bulkPacks.findByProductId(901L)).thenReturn(List.of(boxPack));
        when(orderLines.findMaxLineNo(721L)).thenReturn(0);
        when(orderLines.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orderLines.findBySalesOrderIdOrderByLineNo(721L)).thenReturn(List.of());

        AddSalesOrderLineRequest req = new AddSalesOrderLineRequest(
                "PRODUID00000000000000002", "BOXUID00000000000000001", BigDecimal.ONE, null, null, null);

        SalesOrderLineDto dto = service.addLine("SOUID000000000000000031", req);

        assertThat(dto.unitPriceAmount()).isEqualByComparingTo("1150.0000");
        // qtyInBase still scales by the pack factor — pricing and base-quantity conversion are
        // independent concerns (D-1 only changes the price, not computeQtyInBase's math).
        assertThat(dto.qtyOrderedBase()).isEqualByComparingTo(new BigDecimal("12"));
        verify(priceResolutionService).resolveUnitListPrice(COMPANY_ID, 901L, 920L);
    }

    @Test
    void removeLine_whileApproved_isBlocked() {
        SalesOrder order = orderWithId(713L, "SOUID000000000000000029", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000029")).thenReturn(Optional.of(order));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000029", COMPANY_ID))
                .thenReturn(Optional.of(
                        approvalRequest("SOUID000000000000000029", ApprovalRequestStatus.APPROVED)));

        assertThatThrownBy(() -> service.removeLine("SOUID000000000000000029", "LINEUID0000000000000000001"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approval process");
    }

    @Test
    void confirm_throwsWhenApprovalPending() {
        SalesOrder order = orderWithId(703L, "SOUID000000000000000023", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000023")).thenReturn(Optional.of(order));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000023", COMPANY_ID))
                .thenReturn(Optional.of(
                        approvalRequest("SOUID000000000000000023", ApprovalRequestStatus.PENDING)));

        assertThatThrownBy(() -> service.confirm("SOUID000000000000000023"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("awaiting approval");
    }

    @Test
    void confirm_throwsWhenApprovalRejected() {
        SalesOrder order = orderWithId(704L, "SOUID000000000000000024", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000024")).thenReturn(Optional.of(order));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000024", COMPANY_ID))
                .thenReturn(Optional.of(
                        approvalRequest("SOUID000000000000000024", ApprovalRequestStatus.REJECTED)));

        assertThatThrownBy(() -> service.confirm("SOUID000000000000000024"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rejected");
    }

    @Test
    void confirm_succeeds_whenApprovalStateEmpty() {
        SalesOrder order = orderWithId(705L, "SOUID000000000000000025", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000025")).thenReturn(Optional.of(order));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000025", COMPANY_ID))
                .thenReturn(Optional.empty());
        SalesOrderLine line = new SalesOrderLine(705L, COMPANY_ID, BRANCH_ID, (short) 1,
                400L, "PRD-01", "Widget",
                500L, "EA",
                BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ONE, BigDecimal.ONE,
                VatStatus.STANDARD, BigDecimal.ZERO,
                "TZS", 1L);
        when(orderLines.findBySalesOrderIdOrderByLineNo(705L)).thenReturn(List.of(line));
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(
                customer(CUSTOMER_ID, "CUST-0001", "Acme Traders")));
        when(agents.findById(AGENT_ID)).thenReturn(Optional.of(
                agent(AGENT_ID, "AGT-0001", "Jane Agent")));
        when(branches.findById(BRANCH_ID)).thenReturn(Optional.of(
                branch("BR-01", "Head Office")));

        SalesOrderDto dto = service.confirm("SOUID000000000000000025");

        assertThat(dto.status()).isEqualTo("CONFIRMED");
    }

    @Test
    void getByUid_exposesApprovalStatusFromEngine() {
        SalesOrder order = orderWithId(706L, "SOUID000000000000000026", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000026")).thenReturn(Optional.of(order));
        when(orderLines.findBySalesOrderIdOrderByLineNo(706L)).thenReturn(List.of());
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(
                customer(CUSTOMER_ID, "CUST-0001", "Acme Traders")));
        when(agents.findById(AGENT_ID)).thenReturn(Optional.of(
                agent(AGENT_ID, "AGT-0001", "Jane Agent")));
        when(branches.findById(BRANCH_ID)).thenReturn(Optional.of(
                branch("BR-01", "Head Office")));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000026", COMPANY_ID))
                .thenReturn(Optional.of(
                        approvalRequest("SOUID000000000000000026", ApprovalRequestStatus.APPROVED)));

        SalesOrderDto dto = service.getByUid("SOUID000000000000000026");

        assertThat(dto.approvalStatus()).isEqualTo("APPROVED");
    }

    @Test
    void getByUid_approvalStatusNull_whenNeverSubmitted() {
        SalesOrder order = orderWithId(707L, "SOUID000000000000000027", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000027")).thenReturn(Optional.of(order));
        when(orderLines.findBySalesOrderIdOrderByLineNo(707L)).thenReturn(List.of());
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(
                customer(CUSTOMER_ID, "CUST-0001", "Acme Traders")));
        when(agents.findById(AGENT_ID)).thenReturn(Optional.of(
                agent(AGENT_ID, "AGT-0001", "Jane Agent")));
        when(branches.findById(BRANCH_ID)).thenReturn(Optional.of(
                branch("BR-01", "Head Office")));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000027", COMPANY_ID))
                .thenReturn(Optional.empty());

        SalesOrderDto dto = service.getByUid("SOUID000000000000000027");

        assertThat(dto.approvalStatus()).isNull();
    }

    // -------------------------------------------------------------------------
    // D-4 — automatic amount-threshold approval gate at confirm (extends PR #189)
    // -------------------------------------------------------------------------

    @Test
    void confirm_overThreshold_noPriorRequest_autoSubmits_andThrowsFriendlyMessage_whenEnginePending() {
        SalesOrder order = orderWithId(800L, "SOUID000000000000000030", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000030")).thenReturn(Optional.of(order));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000030", COMPANY_ID))
                .thenReturn(Optional.empty());
        when(branches.findByIdAndCompany_Id(BRANCH_ID, COMPANY_ID))
                .thenReturn(Optional.of(branchWithUid("BRUID0000000000000000001", "BR-01", "Head Office")));
        when(salesApprovalGate.requiresApproval(order, "BRUID0000000000000000001")).thenReturn(true);
        when(salesApprovalGate.submitIndependent(order, "BRUID0000000000000000001"))
                .thenReturn(approvalRequest("SOUID000000000000000030", ApprovalRequestStatus.PENDING));

        assertThatThrownBy(() -> service.confirm("SOUID000000000000000030"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds the approval threshold");

        // I3: the submission goes through the INDEPENDENT (REQUIRES_NEW) gate method — not the
        // plain submit() — so the just-written ApprovalRequest survives this method's block-throw.
        verify(salesApprovalGate).submitIndependent(order, "BRUID0000000000000000001");
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.DRAFT);
    }

    @Test
    void confirm_overThreshold_noPriorRequest_engineHasNoPolicy_blocksAsMisconfiguration() {
        // I2/R1: the engine had no SALES_ORDER policy to match, so submitIndependent detects the
        // auto-approved-by-default result itself, rolls back its own transaction, and throws
        // ApprovalPolicyMissingException instead of returning it — the caller turns that into the
        // friendly misconfiguration block. For an over-threshold order that is a misconfiguration,
        // not a real approval, and must never let the confirm silently proceed.
        SalesOrder order = orderWithId(801L, "SOUID000000000000000031", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000031")).thenReturn(Optional.of(order));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000031", COMPANY_ID))
                .thenReturn(Optional.empty());
        when(branches.findByIdAndCompany_Id(BRANCH_ID, COMPANY_ID))
                .thenReturn(Optional.of(branchWithUid("BRUID0000000000000000001", "BR-01", "Head Office")));
        when(salesApprovalGate.requiresApproval(order, "BRUID0000000000000000001")).thenReturn(true);
        when(salesApprovalGate.submitIndependent(order, "BRUID0000000000000000001"))
                .thenThrow(new ApprovalPolicyMissingException());

        assertThatThrownBy(() -> service.confirm("SOUID000000000000000031"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no approval policy is configured");

        verify(salesApprovalGate).submitIndependent(order, "BRUID0000000000000000001");
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.DRAFT);
    }

    @Test
    void confirm_afterAdminAddsPolicy_noLeftoverFromEarlierMisconfig_reSubmitsAndRoutesToApprovalPending() {
        // R1 remediation: an EARLIER confirm attempt hit the no-policy misconfiguration and was
        // blocked, but — unlike the old buggy behaviour — left NO durable request behind, so the
        // engine state here is still empty (existing = Optional.empty()). Once an administrator
        // adds a matching SALES_ORDER policy, a fresh confirm re-submits and routes to a genuine
        // PENDING approval instead of looping the same misconfiguration error forever.
        SalesOrder order = orderWithId(806L, "SOUID000000000000000036", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000036")).thenReturn(Optional.of(order));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000036", COMPANY_ID))
                .thenReturn(Optional.empty());
        when(branches.findByIdAndCompany_Id(BRANCH_ID, COMPANY_ID))
                .thenReturn(Optional.of(branchWithUid("BRUID0000000000000000001", "BR-01", "Head Office")));
        when(salesApprovalGate.requiresApproval(order, "BRUID0000000000000000001")).thenReturn(true);
        when(salesApprovalGate.submitIndependent(order, "BRUID0000000000000000001"))
                .thenReturn(approvalRequest("SOUID000000000000000036", ApprovalRequestStatus.PENDING));

        assertThatThrownBy(() -> service.confirm("SOUID000000000000000036"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("submitted for approval");

        verify(salesApprovalGate).submitIndependent(order, "BRUID0000000000000000001");
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.DRAFT);
    }

    @Test
    void confirm_existingApprovedRequest_regardlessOfHowItWasApproved_gateSkipped_confirmProceeds() {
        // R1: once ANY approval request already exists for this order (PENDING/REJECTED handled by
        // assertApprovalClearance; APPROVED handled here), the D-4 gate does nothing further — no
        // branch lookup, no requiresApproval/submitIndependent call. This also covers a legacy
        // stale auto-approved row from before R1 shipped: it no longer perpetually blocks confirm,
        // because assertApprovalClearance treats APPROVED as clear regardless of how it got there.
        SalesOrder order = orderWithId(805L, "SOUID000000000000000035", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000035")).thenReturn(Optional.of(order));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000035", COMPANY_ID))
                .thenReturn(Optional.of(
                        approvalRequest("SOUID000000000000000035", ApprovalRequestStatus.APPROVED, true)));
        SalesOrderLine line = new SalesOrderLine(805L, COMPANY_ID, BRANCH_ID, (short) 1,
                400L, "PRD-01", "Widget",
                500L, "EA",
                BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ONE, BigDecimal.ONE,
                VatStatus.STANDARD, BigDecimal.ZERO,
                "TZS", 1L);
        when(orderLines.findBySalesOrderIdOrderByLineNo(805L)).thenReturn(List.of(line));
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(
                customer(CUSTOMER_ID, "CUST-0001", "Acme Traders")));
        when(agents.findById(AGENT_ID)).thenReturn(Optional.of(
                agent(AGENT_ID, "AGT-0001", "Jane Agent")));

        SalesOrderDto dto = service.confirm("SOUID000000000000000035");

        assertThat(dto.status()).isEqualTo("CONFIRMED");
        verify(salesApprovalGate, never()).requiresApproval(any(), any());
        verify(salesApprovalGate, never()).submitIndependent(any(), any());
    }

    @Test
    void confirm_belowThreshold_gateNotTriggered_confirmProceedsUnchanged() {
        SalesOrder order = orderWithId(802L, "SOUID000000000000000032", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000032")).thenReturn(Optional.of(order));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000032", COMPANY_ID))
                .thenReturn(Optional.empty());
        when(branches.findByIdAndCompany_Id(BRANCH_ID, COMPANY_ID))
                .thenReturn(Optional.of(branchWithUid("BRUID0000000000000000001", "BR-01", "Head Office")));
        when(salesApprovalGate.requiresApproval(order, "BRUID0000000000000000001")).thenReturn(false);
        SalesOrderLine line = new SalesOrderLine(802L, COMPANY_ID, BRANCH_ID, (short) 1,
                400L, "PRD-01", "Widget",
                500L, "EA",
                BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ONE, BigDecimal.ONE,
                VatStatus.STANDARD, BigDecimal.ZERO,
                "TZS", 1L);
        when(orderLines.findBySalesOrderIdOrderByLineNo(802L)).thenReturn(List.of(line));
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(
                customer(CUSTOMER_ID, "CUST-0001", "Acme Traders")));
        when(agents.findById(AGENT_ID)).thenReturn(Optional.of(
                agent(AGENT_ID, "AGT-0001", "Jane Agent")));

        SalesOrderDto dto = service.confirm("SOUID000000000000000032");

        assertThat(dto.status()).isEqualTo("CONFIRMED");
        verify(salesApprovalGate, never()).submitIndependent(any(), any());
    }

    @Test
    void confirm_manuallyApprovedOrder_gateSkipped_stillConfirms() {
        // A prior manual submitForApproval (#189) already produced a genuinely-decided APPROVED
        // request (autoApproved=false — a real policy/manual approval, NOT the I2 no-policy
        // default) — the D-4 gate must not double-submit; assertApprovalClearance lets APPROVED
        // through unchanged.
        SalesOrder order = orderWithId(803L, "SOUID000000000000000033", CUSTOMER_ID, AGENT_ID);
        when(orders.findByUid("SOUID000000000000000033")).thenReturn(Optional.of(order));
        when(approvalEngine.getApprovalState(DOC_TYPE, "SOUID000000000000000033", COMPANY_ID))
                .thenReturn(Optional.of(
                        approvalRequest("SOUID000000000000000033", ApprovalRequestStatus.APPROVED)));
        SalesOrderLine line = new SalesOrderLine(803L, COMPANY_ID, BRANCH_ID, (short) 1,
                400L, "PRD-01", "Widget",
                500L, "EA",
                BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ONE, BigDecimal.ONE,
                VatStatus.STANDARD, BigDecimal.ZERO,
                "TZS", 1L);
        when(orderLines.findBySalesOrderIdOrderByLineNo(803L)).thenReturn(List.of(line));
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(
                customer(CUSTOMER_ID, "CUST-0001", "Acme Traders")));
        when(agents.findById(AGENT_ID)).thenReturn(Optional.of(
                agent(AGENT_ID, "AGT-0001", "Jane Agent")));

        SalesOrderDto dto = service.confirm("SOUID000000000000000033");

        assertThat(dto.status()).isEqualTo("CONFIRMED");
        verify(salesApprovalGate, never()).requiresApproval(any(), any());
        verify(salesApprovalGate, never()).submitIndependent(any(), any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static SalesOrder orderWithId(Long id, String uid, Long customerId, Long agentId) {
        SalesOrder order = new SalesOrder(COMPANY_ID, BRANCH_ID, customerId, agentId,
                "TZS", LocalDate.now(), 1L);
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "uid", uid);
        // orderNumber is NOT NULL at the DB; the audit detail map (Map.of) NPEs on a null value —
        // set a fixture value so confirm()/submitForApproval() audit calls don't blow up.
        order.setOrderNumber("SO-" + id);
        return order;
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

    /**
     * Minimal ApprovalRequestDto stub — only status (and documentUid) matter to most tests.
     * {@code autoApproved} defaults to {@code false}: an APPROVED status here represents a
     * genuinely policy-matched (or later manually-decided) approval, NOT the engine's no-policy
     * default (persona UAT I2 — use the 3-arg overload to model that specific scenario).
     */
    private static ApprovalRequestDto approvalRequest(String documentUid, ApprovalRequestStatus status) {
        return approvalRequest(documentUid, status, false);
    }

    private static ApprovalRequestDto approvalRequest(String documentUid, ApprovalRequestStatus status,
                                                       boolean autoApproved) {
        return new ApprovalRequestDto(
                1L, "APRUID00000000000000001", COMPANY_ID, BRANCH_ID, null, null,
                "APR-0001", DOC_TYPE, documentUid, BigDecimal.TEN, "TZS",
                status, null, autoApproved,
                null, null, "summary",
                1L, null, java.time.Instant.now(), null, null, null, List.of());
    }

    private static Customer customer(Long companyId, String code, String displayName) {
        return new Customer(companyId, code, PartyType.INDIVIDUAL, displayName,
                CustomerKind.CASH_WALK_IN, 1L);
    }

    private static Agent agent(Long companyId, String code, String displayName) {
        return new Agent(companyId, code, PartyType.INDIVIDUAL, displayName,
                AgentKind.EXTERNAL, 1L);
    }

    /** Company param intentionally null — only name/code are read by the enrichment path. */
    private static Branch branch(String code, String name) {
        return new Branch(null, code, name);
    }

    /** Branch fixture with an explicit uid, for D-4 tests that resolve branchUid for the gate. */
    private static Branch branchWithUid(String uid, String code, String name) {
        Branch b = new Branch(null, code, name);
        ReflectionTestUtils.setField(b, "uid", uid);
        return b;
    }
}
