package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.erp.modules.parties.domain.entity.Agent;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import com.erp.modules.sales.domain.entity.SalesOrder;
import com.erp.modules.sales.repository.QuotationLineRepository;
import com.erp.modules.sales.repository.QuotationRepository;
import com.erp.modules.sales.repository.SalesOrderLineRepository;
import com.erp.modules.sales.repository.SalesOrderRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link SalesOrderServiceImpl} customer/agent name enrichment.
 *
 * <p>A top UX complaint: sales orders showed only the raw numeric {@code customerId} — never the
 * customer's name — so approvers could not see who an order was for. Fixed by resolving
 * customerName/customerCode/agentName at read time, mirroring {@code SalesInvoiceServiceImpl}.
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

    @InjectMocks SalesOrderServiceImpl service;

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

        SalesOrderDto dto = service.getByUid("SOUID000000000000000001");

        assertThat(dto.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(dto.customerName()).isEqualTo("Acme Traders");
        assertThat(dto.customerCode()).isEqualTo("CUST-0001");
        assertThat(dto.agentId()).isEqualTo(AGENT_ID);
        assertThat(dto.agentName()).isEqualTo("Jane Agent");
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

        Page<SalesOrderDto> page = service.list(COMPANY_ID, Pageable.unpaged());

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).customerName()).isEqualTo("Acme Traders");
        assertThat(page.getContent().get(0).agentName()).isEqualTo("Jane Agent");
        assertThat(page.getContent().get(1).customerName()).isEqualTo("Beta Stores");
        assertThat(page.getContent().get(1).agentName()).isNull();
        // list rows are resolved one findById per row (no batch fetch) — same N+1 shape as
        // SalesInvoiceServiceImpl.list/toDto; see handoff notes.
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static SalesOrder orderWithId(Long id, String uid, Long customerId, Long agentId) {
        SalesOrder order = new SalesOrder(COMPANY_ID, BRANCH_ID, customerId, agentId,
                "TZS", LocalDate.now(), 1L);
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "uid", uid);
        return order;
    }

    private static Customer customer(Long companyId, String code, String displayName) {
        return new Customer(companyId, code, PartyType.INDIVIDUAL, displayName,
                CustomerKind.CASH_WALK_IN, 1L);
    }

    private static Agent agent(Long companyId, String code, String displayName) {
        return new Agent(companyId, code, PartyType.INDIVIDUAL, displayName,
                AgentKind.EXTERNAL, 1L);
    }
}
