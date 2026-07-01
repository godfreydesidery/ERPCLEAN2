package com.erp.modules.routes.service;

import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import org.springframework.stereotype.Component;

/**
 * Enforces the three cross-entity rules that no single-row DB CHECK can see (ADR-0012 D-8):
 *
 * <ol>
 *   <li><b>Same-company guard (BR-ROUTE-03):</b> every assigned customer/agent/branch must belong
 *       to the same company as the route.
 *   <li><b>EXTERNAL-only guard (BR-ROUTE-02):</b> only EXTERNAL agents may be route-assigned;
 *       an INTERNAL agent is rejected (422/ForbiddenException).
 *   <li><b>Resolution helpers:</b> uid → id lookup returning the scalar Long id the service persists.
 * </ol>
 *
 * <p>Mirrors {@link com.erp.modules.parties.service.PartyBranchGuard}. Cross-module reads of
 * {@code CustomerRepository}, {@code AgentRepository}, and {@code BranchRepository} are the same
 * boundary pattern used by {@code PartyBranchGuard} (ADR-0006 D-4) and {@code ScopeGuard}.
 */
@Component
public class RouteAssignmentGuard {

    private final CustomerRepository customers;
    private final AgentRepository agents;
    private final BranchRepository branches;

    public RouteAssignmentGuard(CustomerRepository customers,
                                AgentRepository agents,
                                BranchRepository branches) {
        this.customers = customers;
        this.agents = agents;
        this.branches = branches;
    }

    /**
     * Resolves the customer uid to its scalar id and asserts same-company as the route (BR-ROUTE-03).
     *
     * @param routeCompanyId the company the route belongs to
     * @param customerUid    the customer being assigned
     * @return the customer's internal id
     * @throws NotFoundException  if the customer does not exist
     * @throws ForbiddenException if the customer belongs to a different company (BR-ROUTE-03)
     */
    public Long resolveCustomerAndAssertSameCompany(Long routeCompanyId, String customerUid) {
        var customer = customers.findByUid(customerUid)
                .orElseThrow(() -> new NotFoundException("Customer not found."));
        if (!customer.getCompanyId().equals(routeCompanyId)) {
            // BR-ROUTE-03: all assigned parties must belong to the same company as the route.
            throw new ForbiddenException(
                    "This customer does not belong to the same company as the route.");
        }
        return customer.getId();
    }

    /**
     * Resolves the agent uid to its scalar id, asserts EXTERNAL kind (BR-ROUTE-02) and same-company
     * as the route (BR-ROUTE-03).
     *
     * @param routeCompanyId the company the route belongs to
     * @param agentUid       the agent being assigned
     * @return the agent's internal id
     * @throws NotFoundException  if the agent does not exist
     * @throws ForbiddenException if the agent is INTERNAL (BR-ROUTE-02) or wrong company (BR-ROUTE-03)
     */
    public Long resolveAgentAndAssertExternalSameCompany(Long routeCompanyId, String agentUid) {
        var agent = agents.findByUid(agentUid)
                .orElseThrow(() -> new NotFoundException("Agent not found."));
        if (agent.getAgentKind() == AgentKind.INTERNAL) {
            // BR-ROUTE-02: only EXTERNAL agents may be assigned to a route.
            throw new ForbiddenException(
                    "Only EXTERNAL agents may be assigned to a route.");
        }
        if (!agent.getCompanyId().equals(routeCompanyId)) {
            // BR-ROUTE-03: all assigned parties must belong to the same company as the route.
            throw new ForbiddenException(
                    "This agent does not belong to the same company as the route.");
        }
        return agent.getId();
    }

    /**
     * Resolves the branch uid to its scalar id and asserts same-company as the route (BR-ROUTE-03).
     *
     * @param routeCompanyId the company the route belongs to
     * @param branchUid      the branch being associated
     * @return the branch's internal id
     * @throws NotFoundException  if the branch does not exist
     * @throws ForbiddenException if the branch belongs to a different company (BR-ROUTE-03)
     */
    public Long resolveBranchAndAssertSameCompany(Long routeCompanyId, String branchUid) {
        var branch = branches.findByUid(branchUid)
                .orElseThrow(() -> new NotFoundException("Branch not found."));
        Long branchCompanyId = branch.getCompany().getId();
        if (!branchCompanyId.equals(routeCompanyId)) {
            // BR-ROUTE-03: all assigned parties must belong to the same company as the route.
            throw new ForbiddenException(
                    "This branch does not belong to the same company as the route.");
        }
        return branch.getId();
    }
}
