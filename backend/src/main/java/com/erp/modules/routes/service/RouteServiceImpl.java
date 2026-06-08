package com.erp.modules.routes.service;

import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.routes.domain.dto.AssignRouteAgentRequest;
import com.erp.modules.routes.domain.dto.AssignRouteBranchRequest;
import com.erp.modules.routes.domain.dto.AssignRouteCustomerRequest;
import com.erp.modules.routes.domain.dto.CreateRouteRequest;
import com.erp.modules.routes.domain.dto.RouteAgentDto;
import com.erp.modules.routes.domain.dto.RouteBranchDto;
import com.erp.modules.routes.domain.dto.RouteCustomerDto;
import com.erp.modules.routes.domain.dto.RouteDto;
import com.erp.modules.routes.domain.dto.UpdateRouteRequest;
import com.erp.modules.routes.domain.entity.Route;
import com.erp.modules.routes.domain.entity.RouteAgent;
import com.erp.modules.routes.domain.entity.RouteBranch;
import com.erp.modules.routes.domain.entity.RouteCustomer;
import com.erp.modules.routes.repository.RouteAgentRepository;
import com.erp.modules.routes.repository.RouteBranchRepository;
import com.erp.modules.routes.repository.RouteCustomerRepository;
import com.erp.modules.routes.repository.RouteRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Route master administration (FR-ROUTE-01..17, ADR-0012).
 *
 * <p>Security: {@code scopeGuard.assertCanActIn} on EVERY read path (ADR-0012 D-8, the
 * #1 anti-regression guard). Cross-tenant assignment targets resolved and same-company-checked
 * via {@link RouteAssignmentGuard} on every assign.
 */
@Service
@Transactional
public class RouteServiceImpl implements RouteService {

    private final RouteRepository routes;
    private final RouteCustomerRepository routeCustomers;
    private final RouteAgentRepository routeAgents;
    private final RouteBranchRepository routeBranches;
    private final RouteCodeGenerator codeGen;
    private final RouteAssignmentGuard assignmentGuard;
    private final CompanyRepository companies;
    private final CustomerRepository customers;
    private final AgentRepository agents;
    private final BranchRepository branches;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;
    private final EntityManager em;

    public RouteServiceImpl(RouteRepository routes,
                            RouteCustomerRepository routeCustomers,
                            RouteAgentRepository routeAgents,
                            RouteBranchRepository routeBranches,
                            RouteCodeGenerator codeGen,
                            RouteAssignmentGuard assignmentGuard,
                            CompanyRepository companies,
                            CustomerRepository customers,
                            AgentRepository agents,
                            BranchRepository branches,
                            ScopeGuard scopeGuard,
                            AuditService audit,
                            EntityManager em) {
        this.routes = routes;
        this.routeCustomers = routeCustomers;
        this.routeAgents = routeAgents;
        this.routeBranches = routeBranches;
        this.codeGen = codeGen;
        this.assignmentGuard = assignmentGuard;
        this.companies = companies;
        this.customers = customers;
        this.agents = agents;
        this.branches = branches;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
        this.em = em;
    }

    // -------------------------------------------------------------------------
    // Core CRUD
    // -------------------------------------------------------------------------

    @Override
    public RouteDto create(CreateRouteRequest req) {
        Long companyId = resolveCompanyId(req.companyUid());
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String code = codeGen.next(companyId);
        Route route = new Route(companyId, code, req.name(), actorId());
        route.setLocationIdentifier(req.locationIdentifier());

        Route saved = routes.save(route);
        audit.record(AuditEvent.of(AuditActions.ROUTE_CREATE, "routes", saved.getId(), saved.getUid())
                .detail(Map.of("code", saved.getCode(), "name", saved.getName())));

        return RouteDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public RouteDto getByUid(String uid) {
        Route r = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), r.getCompanyId());
        return RouteDto.from(r);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RouteDto> list(Long companyId, String q, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (q != null && !q.isBlank()) {
            return routes.search(companyId, q, pageable).map(RouteDto::from);
        }
        return routes.findByCompanyId(companyId, pageable).map(RouteDto::from);
    }

    @Override
    public RouteDto updateByUid(String uid, UpdateRouteRequest req) {
        Route r = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), r.getCompanyId());

        r.setName(req.name());
        r.setLocationIdentifier(req.locationIdentifier());
        r.setUpdatedAt(Instant.now());
        r.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.ROUTE_UPDATE, "routes", r.getId(), r.getUid()));
        return RouteDto.from(r);
    }

    @Override
    public void archiveByUid(String uid) {
        Route r = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), r.getCompanyId());
        MasterStatus prev = r.getStatus();
        r.setStatus(MasterStatus.ARCHIVED);
        r.setUpdatedAt(Instant.now());
        r.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.ROUTE_ARCHIVE, "routes", r.getId(), r.getUid())
                .detail(Map.of("previousStatus", prev.name(), "newStatus", MasterStatus.ARCHIVED.name())));
    }

    @Override
    public void restoreByUid(String uid) {
        Route r = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), r.getCompanyId());
        MasterStatus prev = r.getStatus();
        r.setStatus(MasterStatus.ACTIVE);
        r.setUpdatedAt(Instant.now());
        r.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.ROUTE_RESTORE, "routes", r.getId(), r.getUid())
                .detail(Map.of("previousStatus", prev.name(), "newStatus", MasterStatus.ACTIVE.name())));
    }

    // -------------------------------------------------------------------------
    // Route ↔ Customer
    // -------------------------------------------------------------------------

    @Override
    public RouteCustomerDto assignCustomer(String routeUid, AssignRouteCustomerRequest req) {
        Route route = require(routeUid);
        scopeGuard.assertCanActIn(RequestContext.get(), route.getCompanyId());

        Long customerId = assignmentGuard.resolveCustomerAndAssertSameCompany(
                route.getCompanyId(), req.customerUid());

        if (routeCustomers.findByRouteIdAndCustomerId(route.getId(), customerId).isPresent()) {
            throw new ConflictException("Customer is already assigned to this route.");
        }

        RouteCustomer rc = routeCustomers.save(new RouteCustomer(route, customerId, actorId()));
        audit.record(AuditEvent.of(AuditActions.ROUTE_CUSTOMER_ADD, "route_customer",
                route.getId(), route.getUid())
                .detail(Map.of("customerUid", req.customerUid())));

        return enrichCustomer(rc);
    }

    @Override
    public void unassignCustomer(String routeUid, String customerUid) {
        Route route = require(routeUid);
        scopeGuard.assertCanActIn(RequestContext.get(), route.getCompanyId());

        Long customerId = assignmentGuard.resolveCustomerAndAssertSameCompany(
                route.getCompanyId(), customerUid);

        routeCustomers.deleteByRouteIdAndCustomerId(route.getId(), customerId);
        audit.record(AuditEvent.of(AuditActions.ROUTE_CUSTOMER_REMOVE, "route_customer",
                route.getId(), route.getUid())
                .detail(Map.of("customerUid", customerUid)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RouteCustomerDto> listCustomers(String routeUid) {
        Route route = require(routeUid);
        scopeGuard.assertCanActIn(RequestContext.get(), route.getCompanyId());
        return routeCustomers.findByRouteId(route.getId()).stream()
                .map(this::enrichCustomer)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Route ↔ Agent
    // -------------------------------------------------------------------------

    @Override
    public RouteAgentDto assignAgent(String routeUid, AssignRouteAgentRequest req) {
        Route route = require(routeUid);
        scopeGuard.assertCanActIn(RequestContext.get(), route.getCompanyId());

        Long agentId = assignmentGuard.resolveAgentAndAssertExternalSameCompany(
                route.getCompanyId(), req.agentUid());

        if (routeAgents.findByRouteIdAndAgentId(route.getId(), agentId).isPresent()) {
            throw new ConflictException("Agent is already assigned to this route.");
        }

        // If setting as primary: clear any prior primary for this agent (BR-ROUTE-04).
        // Flush immediately after so the partial unique index (WHERE is_primary) sees the UPDATE
        // before the new primary INSERT — otherwise Postgres fires the constraint mid-batch.
        if (req.isPrimary()) {
            routeAgents.findByAgentIdAndIsPrimaryTrueAndRouteIdNot(agentId, route.getId())
                    .forEach(prior -> {
                        prior.setPrimary(false);
                        routeAgents.save(prior);
                        audit.record(AuditEvent.of(AuditActions.ROUTE_AGENT_CLEARPRIMARY,
                                "route_agent", prior.getRoute().getId(), prior.getRoute().getUid())
                                .detail(Map.of("agentUid", req.agentUid(), "isPrimary", "false")));
                    });
            em.flush(); // push the UPDATE(s) to DB before inserting the new primary row
        }

        RouteAgent ra = routeAgents.save(new RouteAgent(route, agentId, req.isPrimary(), actorId()));
        audit.record(AuditEvent.of(AuditActions.ROUTE_AGENT_ADD, "route_agent",
                route.getId(), route.getUid())
                .detail(Map.of("agentUid", req.agentUid(), "isPrimary", String.valueOf(req.isPrimary()))));

        if (req.isPrimary()) {
            audit.record(AuditEvent.of(AuditActions.ROUTE_AGENT_SETPRIMARY, "route_agent",
                    route.getId(), route.getUid())
                    .detail(Map.of("agentUid", req.agentUid(), "isPrimary", "true")));
        }

        return enrichAgent(ra);
    }

    @Override
    public void unassignAgent(String routeUid, String agentUid) {
        Route route = require(routeUid);
        scopeGuard.assertCanActIn(RequestContext.get(), route.getCompanyId());

        Long agentId = assignmentGuard.resolveAgentAndAssertExternalSameCompany(
                route.getCompanyId(), agentUid);

        // If the agent being unassigned was primary on this route, note it (BR-ROUTE-04 clear-on-unassign).
        routeAgents.findByRouteIdAndAgentId(route.getId(), agentId).ifPresent(ra -> {
            if (ra.isPrimary()) {
                audit.record(AuditEvent.of(AuditActions.ROUTE_AGENT_CLEARPRIMARY, "route_agent",
                        route.getId(), route.getUid())
                        .detail(Map.of("agentUid", agentUid, "isPrimary", "false",
                                "reason", "unassigned")));
            }
        });

        routeAgents.deleteByRouteIdAndAgentId(route.getId(), agentId);
        audit.record(AuditEvent.of(AuditActions.ROUTE_AGENT_REMOVE, "route_agent",
                route.getId(), route.getUid())
                .detail(Map.of("agentUid", agentUid)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RouteAgentDto> listAgents(String routeUid) {
        Route route = require(routeUid);
        scopeGuard.assertCanActIn(RequestContext.get(), route.getCompanyId());
        return routeAgents.findByRouteId(route.getId()).stream()
                .map(this::enrichAgent)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Route ↔ Branch
    // -------------------------------------------------------------------------

    @Override
    public RouteBranchDto assignBranch(String routeUid, AssignRouteBranchRequest req) {
        Route route = require(routeUid);
        scopeGuard.assertCanActIn(RequestContext.get(), route.getCompanyId());

        Long branchId = assignmentGuard.resolveBranchAndAssertSameCompany(
                route.getCompanyId(), req.branchUid());

        if (routeBranches.findByRouteIdAndBranchId(route.getId(), branchId).isPresent()) {
            throw new ConflictException("Branch is already associated with this route.");
        }

        RouteBranch rb = routeBranches.save(new RouteBranch(route, branchId, actorId()));
        audit.record(AuditEvent.of(AuditActions.ROUTE_BRANCH_ADD, "routes",
                route.getId(), route.getUid())
                .detail(Map.of("branchUid", req.branchUid())));

        return enrichBranch(rb);
    }

    @Override
    public void unassignBranch(String routeUid, String branchUid) {
        Route route = require(routeUid);
        scopeGuard.assertCanActIn(RequestContext.get(), route.getCompanyId());

        Long branchId = assignmentGuard.resolveBranchAndAssertSameCompany(
                route.getCompanyId(), branchUid);

        routeBranches.deleteByRouteIdAndBranchId(route.getId(), branchId);
        audit.record(AuditEvent.of(AuditActions.ROUTE_BRANCH_REMOVE, "routes",
                route.getId(), route.getUid())
                .detail(Map.of("branchUid", branchUid)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RouteBranchDto> listBranches(String routeUid) {
        Route route = require(routeUid);
        scopeGuard.assertCanActIn(RequestContext.get(), route.getCompanyId());
        return routeBranches.findByRouteId(route.getId()).stream()
                .map(this::enrichBranch)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Invoice-default lookup (ADR-0012 D-6b)
    // -------------------------------------------------------------------------

    /**
     * Find the primary route id for the given agent at the given branch, scoped to company.
     * Returns empty (blank-on-ambiguity per OQ-ROUTE-01) if: the agent has no primary route,
     * the primary route is not associated with the active branch, or the primary route is not ACTIVE.
     * Never throws — fail open to empty so the sale is never blocked (BR-ROUTE-05).
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Long> findPrimaryRouteIdForAgentAtBranch(Long companyId, Long agentId,
                                                              Long branchId) {
        try {
            return routeAgents.findByAgentIdAndIsPrimaryTrue(agentId)
                    .filter(ra -> {
                        Route r = ra.getRoute();
                        // Must be in the same company (sanity guard)
                        if (!r.getCompanyId().equals(companyId)) {
                            return false;
                        }
                        // Must be ACTIVE (BR-ROUTE-07)
                        if (r.getStatus() != MasterStatus.ACTIVE) {
                            return false;
                        }
                        // Must be associated with the active branch (FR-ROUTE-12)
                        return routeBranches.findByRouteIdAndBranchId(r.getId(), branchId).isPresent();
                    })
                    .map(ra -> ra.getRoute().getId());
        } catch (Exception ex) {
            // Fail open — invoice creation must never be blocked by route default lookup (BR-ROUTE-05)
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Route require(String uid) {
        return Lookups.orNotFound(routes.findByUid(uid), "Route", uid);
    }

    private Long resolveCompanyId(String companyUid) {
        return companies.findByUid(companyUid)
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyUid));
    }

    private RouteCustomerDto enrichCustomer(RouteCustomer rc) {
        return customers.findById(rc.getCustomerId())
                .map(c -> RouteCustomerDto.from(rc, c.getUid(), c.getCode(), c.getDisplayName()))
                .orElseGet(() -> RouteCustomerDto.from(rc, null, null, null));
    }

    private RouteAgentDto enrichAgent(RouteAgent ra) {
        return agents.findById(ra.getAgentId())
                .map(a -> RouteAgentDto.from(ra, a.getUid(), a.getCode(), a.getDisplayName(),
                        a.getAgentKind()))
                .orElseGet(() -> RouteAgentDto.from(ra, null, null, null, null));
    }

    private RouteBranchDto enrichBranch(RouteBranch rb) {
        return branches.findById(rb.getBranchId())
                .map(b -> RouteBranchDto.from(rb, b.getUid(), b.getCode(), b.getName()))
                .orElseGet(() -> RouteBranchDto.from(rb, null, null, null));
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
