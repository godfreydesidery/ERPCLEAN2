package com.erp.modules.routes.service;

import com.erp.modules.routes.domain.dto.AssignRouteAgentRequest;
import com.erp.modules.routes.domain.dto.AssignRouteBranchRequest;
import com.erp.modules.routes.domain.dto.AssignRouteCustomerRequest;
import com.erp.modules.routes.domain.dto.CreateRouteRequest;
import com.erp.modules.routes.domain.dto.RouteAgentDto;
import com.erp.modules.routes.domain.dto.RouteBranchDto;
import com.erp.modules.routes.domain.dto.RouteCustomerDto;
import com.erp.modules.routes.domain.dto.RouteDto;
import com.erp.modules.routes.domain.dto.UpdateRouteRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Route master administration (FR-ROUTE-01..17, ADR-0012).
 */
public interface RouteService {

    RouteDto create(CreateRouteRequest request);

    RouteDto getByUid(String uid);

    Page<RouteDto> list(Long companyId, String q, Pageable pageable);

    RouteDto updateByUid(String uid, UpdateRouteRequest request);

    void archiveByUid(String uid);

    void restoreByUid(String uid);

    // Route ↔ Customer
    RouteCustomerDto assignCustomer(String routeUid, AssignRouteCustomerRequest request);

    void unassignCustomer(String routeUid, String customerUid);

    List<RouteCustomerDto> listCustomers(String routeUid);

    // Route ↔ Agent
    RouteAgentDto assignAgent(String routeUid, AssignRouteAgentRequest request);

    void unassignAgent(String routeUid, String agentUid);

    List<RouteAgentDto> listAgents(String routeUid);

    // Route ↔ Branch
    RouteBranchDto assignBranch(String routeUid, AssignRouteBranchRequest request);

    void unassignBranch(String routeUid, String branchUid);

    List<RouteBranchDto> listBranches(String routeUid);

    /**
     * Invoice-default lookup: find the primary route id for the given agent at the given branch,
     * scoped to the company. Returns empty if zero or multiple qualify (OQ-ROUTE-01 blank-on-ambiguity).
     * Called from SalesInvoiceServiceImpl.create (ADR-0012 D-6b). Never throws — fail open to empty.
     */
    Optional<Long> findPrimaryRouteIdForAgentAtBranch(Long companyId, Long agentId, Long branchId);
}
