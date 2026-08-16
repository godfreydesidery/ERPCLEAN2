package com.erp.modules.routes.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.parties.domain.dto.CreateAgentRequest;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.dto.AgentDto;
import com.erp.modules.parties.domain.dto.CustomerDto;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.AgentService;
import com.erp.modules.parties.service.CustomerService;
import com.erp.modules.routes.domain.dto.AssignRouteAgentRequest;
import com.erp.modules.routes.domain.dto.AssignRouteBranchRequest;
import com.erp.modules.routes.domain.dto.AssignRouteCustomerRequest;
import com.erp.modules.routes.domain.dto.CreateRouteRequest;
import com.erp.modules.routes.domain.dto.RouteAgentDto;
import com.erp.modules.routes.domain.dto.RouteBranchDto;
import com.erp.modules.routes.domain.dto.RouteCustomerDto;
import com.erp.modules.routes.domain.dto.RouteDto;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditLog;
import com.erp.platform.audit.AuditRepository;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link RouteServiceImpl} against real Postgres (Testcontainers).
 *
 * <p>Covers (ADR-0012 D-8, the test-plan targets from the spec):
 * <ul>
 *   <li>create → ROUTE-0001, ROUTE-0002; per-company isolation (company B → ROUTE-0001)
 *   <li>assign/unassign customer (N:M — same customer on 2 routes OK)
 *   <li>assign EXTERNAL agent OK; assign INTERNAL agent → rejected (BR-ROUTE-02)
 *   <li>set is_primary → only one primary per agent (setting new clears old)
 *   <li>assign branch; same-company guard on all junctions (BR-ROUTE-03)
 *   <li>cross-tenant read blocked on list / getByUid / child-lists
 *   <li>audit on create + assign (ADR-0012 D-12)
 * </ul>
 */
class RouteServiceImplIT extends PostgresIntegrationTest {

    @Autowired private RouteService routeService;
    @Autowired private CustomerService customerService;
    @Autowired private AgentService agentService;
    @Autowired private AuditRepository auditRepository;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private UserBranchRepository userBranches;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Organisation org;
    private Company companyA;
    private Company companyB;
    private Branch branchA;
    private Branch branchB;
    private Long rootId;

    // seeded parties in companyA
    private String customerAUid;
    private String externalAgentAUid;
    private String internalAgentAUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        org = organisations.save(new Organisation("Route IT Org"));
        companyA = companies.save(new Company(org, "RTCA", "Route Co A"));
        companyB = companies.save(new Company(org, "RTCB", "Route Co B"));
        branchA = branches.save(new Branch(companyA, "RT-A1", "Route Branch A1"));
        branchB = branches.save(new Branch(companyB, "RT-B1", "Route Branch B1"));

        AppUser root = new AppUser("rt_root", passwordEncoder.encode("RootPass1!"), "Route Root");
        root.setRoot(true);
        root = users.save(inOrganisation(root, org.getId()));
        rootId = root.getId();

        // Non-root company-A user — required as appUserId for INTERNAL agents (BR-PARTY-10).
        AppUser internalUser = new AppUser("rt_int_user", passwordEncoder.encode("RootPass1!"), "Internal User A");
        internalUser = users.save(inOrganisation(internalUser, org.getId()));
        userBranches.save(new UserBranch(internalUser.getId(), branchA, rootId));
        final Long internalUserId = internalUser.getId();

        // Root context: canActIn any company
        RequestContext.set(new RequestContext.Principal(
                rootId, "rt_root", true, companyA.getId(), branchA.getId(), null));

        // Seed customer for companyA
        CustomerDto cust = customerService.create(new CreateCustomerRequest(
                companyA.getId(), PartyType.INDIVIDUAL, "Cust A1",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null));
        customerAUid = cust.uid();

        // Seed EXTERNAL agent for companyA
        AgentDto extAgent = agentService.create(new CreateAgentRequest(
                companyA.getId(), PartyType.INDIVIDUAL, "Ext Agent A1",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null));
        externalAgentAUid = extAgent.uid();

        // Seed INTERNAL agent for companyA — references the non-root company member (BR-PARTY-10)
        AgentDto intAgent = agentService.create(new CreateAgentRequest(
                companyA.getId(), PartyType.INDIVIDUAL, "Int Agent A1",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.INTERNAL, internalUserId));
        internalAgentAUid = intAgent.uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // Create: code generation, per-company isolation
    // -----------------------------------------------------------------------

    @Test
    void create_firstRoute_isRoute0001() {
        RouteDto dto = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Kariakoo Route", "Kariakoo market zone"));

        assertThat(dto.code()).isEqualTo("ROUTE-0001");
        assertThat(dto.name()).isEqualTo("Kariakoo Route");
        assertThat(dto.companyId()).isEqualTo(companyA.getId());
        assertThat(dto.uid()).isNotBlank();
    }

    @Test
    void create_secondRoute_isRoute0002() {
        routeService.create(new CreateRouteRequest(companyA.getUid(), "Route 1", null));
        RouteDto dto2 = routeService.create(new CreateRouteRequest(companyA.getUid(), "Route 2", null));
        assertThat(dto2.code()).isEqualTo("ROUTE-0002");
    }

    @Test
    void create_companyBGetsOwnSequence() {
        // companyA has one route; companyB should still start at ROUTE-0001
        routeService.create(new CreateRouteRequest(companyA.getUid(), "A Route", null));

        RequestContext.set(new RequestContext.Principal(
                rootId, "rt_root", true, companyB.getId(), branchB.getId(), null));
        RouteDto dtoB = routeService.create(new CreateRouteRequest(
                companyB.getUid(), "B Route", null));
        assertThat(dtoB.code()).isEqualTo("ROUTE-0001");
    }

    // -----------------------------------------------------------------------
    // Audit on create
    // -----------------------------------------------------------------------

    @Test
    void create_emitsAuditRow() {
        RouteDto dto = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Audit Route", null));

        List<AuditLog> logs = auditRepository.findAll().stream()
                .filter(l -> AuditActions.ROUTE_CREATE.equals(l.getAction()))
                .toList();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getTargetType()).isEqualTo("routes");
        assertThat(logs.get(0).getTargetId()).isEqualTo(dto.id());
    }

    // -----------------------------------------------------------------------
    // Cross-tenant: list blocked for wrong company
    // -----------------------------------------------------------------------

    @Test
    void list_crossTenantBlocked() {
        routeService.create(new CreateRouteRequest(companyA.getUid(), "A Route", null));

        // Switch to non-root principal scoped to companyB — cannot see companyA routes
        RequestContext.set(new RequestContext.Principal(
                rootId, "rt_root", false, companyB.getId(), branchB.getId(), null));

        assertThatThrownBy(() -> routeService.list(companyA.getId(), null, Pageable.unpaged()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getByUid_crossTenantBlocked() {
        RouteDto route = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "A Route", null));

        RequestContext.set(new RequestContext.Principal(
                rootId, "rt_root", false, companyB.getId(), branchB.getId(), null));

        assertThatThrownBy(() -> routeService.getByUid(route.uid()))
                .isInstanceOf(ForbiddenException.class);
    }

    // -----------------------------------------------------------------------
    // Route ↔ Customer: assign / unassign / same-customer-on-2-routes
    // -----------------------------------------------------------------------

    @Test
    void assignCustomer_ok() {
        RouteDto route = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Route A", null));

        RouteCustomerDto rc = routeService.assignCustomer(route.uid(),
                new AssignRouteCustomerRequest(customerAUid));

        assertThat(rc.customerUid()).isEqualTo(customerAUid);
        assertThat(rc.routeId()).isEqualTo(route.id());

        List<RouteCustomerDto> members = routeService.listCustomers(route.uid());
        assertThat(members).hasSize(1);
    }

    @Test
    void assignCustomer_sameCustomerOnTwoRoutes_ok() {
        RouteDto route1 = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Route 1", null));
        RouteDto route2 = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Route 2", null));

        routeService.assignCustomer(route1.uid(), new AssignRouteCustomerRequest(customerAUid));
        routeService.assignCustomer(route2.uid(), new AssignRouteCustomerRequest(customerAUid));

        assertThat(routeService.listCustomers(route1.uid())).hasSize(1);
        assertThat(routeService.listCustomers(route2.uid())).hasSize(1);
    }

    @Test
    void assignCustomer_crossCompany_rejected() {
        // Seed a customer in companyB
        RequestContext.set(new RequestContext.Principal(
                rootId, "rt_root", true, companyB.getId(), branchB.getId(), null));
        CustomerDto custB = customerService.create(new CreateCustomerRequest(
                companyB.getId(), PartyType.INDIVIDUAL, "Cust B1",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null));

        // Back to companyA for the route
        RequestContext.set(new RequestContext.Principal(
                rootId, "rt_root", true, companyA.getId(), branchA.getId(), null));
        RouteDto route = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Route A", null));

        assertThatThrownBy(() -> routeService.assignCustomer(route.uid(),
                new AssignRouteCustomerRequest(custB.uid())))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void unassignCustomer_removesLink() {
        RouteDto route = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Route A", null));
        routeService.assignCustomer(route.uid(), new AssignRouteCustomerRequest(customerAUid));

        routeService.unassignCustomer(route.uid(), customerAUid);

        assertThat(routeService.listCustomers(route.uid())).isEmpty();
    }

    @Test
    void listCustomers_crossTenantBlocked() {
        RouteDto route = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Route A", null));

        RequestContext.set(new RequestContext.Principal(
                rootId, "rt_root", false, companyB.getId(), branchB.getId(), null));

        assertThatThrownBy(() -> routeService.listCustomers(route.uid()))
                .isInstanceOf(ForbiddenException.class);
    }

    // -----------------------------------------------------------------------
    // Route ↔ Agent: EXTERNAL ok; INTERNAL rejected (BR-ROUTE-02)
    // -----------------------------------------------------------------------

    @Test
    void assignAgent_externalAgent_ok() {
        RouteDto route = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Route A", null));

        RouteAgentDto ra = routeService.assignAgent(route.uid(),
                new AssignRouteAgentRequest(externalAgentAUid, false));

        assertThat(ra.agentUid()).isEqualTo(externalAgentAUid);
        assertThat(ra.isPrimary()).isFalse();
    }

    @Test
    void assignAgent_internalAgent_rejected() {
        RouteDto route = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Route A", null));

        assertThatThrownBy(() -> routeService.assignAgent(route.uid(),
                new AssignRouteAgentRequest(internalAgentAUid, false)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("EXTERNAL");
    }

    @Test
    void assignAgent_setPrimary_setsPrimaryFlag() {
        RouteDto route = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Route A", null));

        RouteAgentDto ra = routeService.assignAgent(route.uid(),
                new AssignRouteAgentRequest(externalAgentAUid, true));

        assertThat(ra.isPrimary()).isTrue();
    }

    @Test
    void assignAgent_setPrimaryOnSecondRoute_clearsPreviousPrimary() {
        RouteDto route1 = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Route 1", null));
        RouteDto route2 = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Route 2", null));

        // Assign same EXTERNAL agent to both routes; set primary on route1 first
        routeService.assignAgent(route1.uid(), new AssignRouteAgentRequest(externalAgentAUid, true));
        // Now set primary on route2 — should clear route1's primary
        routeService.assignAgent(route2.uid(), new AssignRouteAgentRequest(externalAgentAUid, true));

        // route1 agent should no longer be primary
        List<RouteAgentDto> agents1 = routeService.listAgents(route1.uid());
        assertThat(agents1).hasSize(1);
        assertThat(agents1.get(0).isPrimary()).isFalse();

        // route2 agent is primary
        List<RouteAgentDto> agents2 = routeService.listAgents(route2.uid());
        assertThat(agents2).hasSize(1);
        assertThat(agents2.get(0).isPrimary()).isTrue();
    }

    @Test
    void assignAgent_crossCompany_rejected() {
        // Seed an EXTERNAL agent in companyB
        RequestContext.set(new RequestContext.Principal(
                rootId, "rt_root", true, companyB.getId(), branchB.getId(), null));
        AgentDto extB = agentService.create(new CreateAgentRequest(
                companyB.getId(), PartyType.INDIVIDUAL, "Ext Agent B1",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null));

        RequestContext.set(new RequestContext.Principal(
                rootId, "rt_root", true, companyA.getId(), branchA.getId(), null));
        RouteDto route = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Route A", null));

        assertThatThrownBy(() -> routeService.assignAgent(route.uid(),
                new AssignRouteAgentRequest(extB.uid(), false)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void unassignAgent_removesLink() {
        RouteDto route = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Route A", null));
        routeService.assignAgent(route.uid(), new AssignRouteAgentRequest(externalAgentAUid, false));

        routeService.unassignAgent(route.uid(), externalAgentAUid);

        assertThat(routeService.listAgents(route.uid())).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Route ↔ Branch
    // -----------------------------------------------------------------------

    @Test
    void assignBranch_ok() {
        RouteDto route = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Route A", null));

        RouteBranchDto rb = routeService.assignBranch(route.uid(),
                new AssignRouteBranchRequest(branchA.getUid()));

        assertThat(rb.branchId()).isEqualTo(branchA.getId());

        List<RouteBranchDto> branchList = routeService.listBranches(route.uid());
        assertThat(branchList).hasSize(1);
    }

    @Test
    void assignBranch_crossCompany_rejected() {
        RouteDto route = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Route A", null));

        assertThatThrownBy(() -> routeService.assignBranch(route.uid(),
                new AssignRouteBranchRequest(branchB.getUid())))
                .isInstanceOf(ForbiddenException.class);
    }

    // -----------------------------------------------------------------------
    // Invoice-default lookup: findPrimaryRouteIdForAgentAtBranch
    // -----------------------------------------------------------------------

    @Test
    void findPrimaryRoute_agentHasPrimaryRouteAtBranch_returnsRouteId() {
        RouteDto route = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Primary Route", null));
        routeService.assignBranch(route.uid(), new AssignRouteBranchRequest(branchA.getUid()));
        routeService.assignAgent(route.uid(), new AssignRouteAgentRequest(externalAgentAUid, true));

        // Resolve the externalAgentAUid to its internal id via the service result
        Long agentId = routeService.listAgents(route.uid()).get(0).agentId();

        Optional<Long> result = routeService.findPrimaryRouteIdForAgentAtBranch(
                companyA.getId(), agentId, branchA.getId());

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(route.id());
    }

    @Test
    void findPrimaryRoute_noPrimaryRoute_returnsEmpty() {
        // Assign agent without isPrimary — agent has no primary route
        RouteDto route = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Non-primary Route", null));
        routeService.assignBranch(route.uid(), new AssignRouteBranchRequest(branchA.getUid()));
        routeService.assignAgent(route.uid(), new AssignRouteAgentRequest(externalAgentAUid, false));

        Long agentId = routeService.listAgents(route.uid()).get(0).agentId();

        Optional<Long> result = routeService.findPrimaryRouteIdForAgentAtBranch(
                companyA.getId(), agentId, branchA.getId());

        assertThat(result).isEmpty();
    }

    @Test
    void findPrimaryRoute_primaryRouteNotAtBranch_returnsEmpty() {
        // Route exists + agent primary but route not associated with branchA
        RouteDto route = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Unassociated Route", null));
        // No branch association
        routeService.assignAgent(route.uid(), new AssignRouteAgentRequest(externalAgentAUid, true));

        Long agentId = routeService.listAgents(route.uid()).get(0).agentId();

        Optional<Long> result = routeService.findPrimaryRouteIdForAgentAtBranch(
                companyA.getId(), agentId, branchA.getId());

        assertThat(result).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Audit on customer assignment
    // -----------------------------------------------------------------------

    @Test
    void assignCustomer_emitsAuditRow() {
        RouteDto route = routeService.create(new CreateRouteRequest(
                companyA.getUid(), "Route A", null));
        routeService.assignCustomer(route.uid(), new AssignRouteCustomerRequest(customerAUid));

        List<AuditLog> logs = auditRepository.findAll().stream()
                .filter(l -> AuditActions.ROUTE_CUSTOMER_ADD.equals(l.getAction()))
                .toList();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getTargetType()).isEqualTo("route_customer");
    }
}
