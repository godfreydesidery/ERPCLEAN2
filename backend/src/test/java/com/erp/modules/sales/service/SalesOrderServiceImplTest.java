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
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import com.erp.modules.sales.domain.entity.SalesOrder;
import com.erp.modules.sales.domain.entity.SalesOrderLine;
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
    @Mock com.erp.modules.products.repository.ProductPriceRepository prices;
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

    /** Minimal ApprovalRequestDto stub — only status (and documentUid) matter to these tests. */
    private static ApprovalRequestDto approvalRequest(String documentUid, ApprovalRequestStatus status) {
        return new ApprovalRequestDto(
                1L, "APRUID00000000000000001", COMPANY_ID, BRANCH_ID, null, null,
                "APR-0001", DOC_TYPE, documentUid, BigDecimal.TEN, "TZS",
                status, null, status == ApprovalRequestStatus.APPROVED,
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
}
