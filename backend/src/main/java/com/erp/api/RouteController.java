package com.erp.api;

import com.erp.modules.routes.domain.dto.AssignRouteAgentRequest;
import com.erp.modules.routes.domain.dto.AssignRouteBranchRequest;
import com.erp.modules.routes.domain.dto.AssignRouteCustomerRequest;
import com.erp.modules.routes.domain.dto.CreateRouteRequest;
import com.erp.modules.routes.domain.dto.RouteAgentDto;
import com.erp.modules.routes.domain.dto.RouteBranchDto;
import com.erp.modules.routes.domain.dto.RouteCustomerDto;
import com.erp.modules.routes.domain.dto.RouteDto;
import com.erp.modules.routes.domain.dto.UpdateRouteRequest;
import com.erp.modules.routes.service.RouteService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Route master administration (FR-ROUTE-01..17, ADR-0012 D-10/D-11).
 * Responses auto-wrapped in {@code ApiResponse<T>} by {@code ApiResponseAdvice}.
 * Gates use {@code @perm.has} / {@code @perm.scoped} — NEVER hasAuthority (brief §4.1).
 */
@RestController
@RequestMapping("/api/v1/routes")
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    // -------------------------------------------------------------------------
    // Core CRUD
    // -------------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("@perm.has('ROUTE.VIEW')")
    public ApiResponse<List<RouteDto>> list(@RequestParam Long companyId,
                                            @RequestParam(required = false) String q,
                                            Pageable pageable) {
        Page<RouteDto> page = routeService.list(companyId, q, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'route','ROUTE.VIEW')")
    public RouteDto get(@PathVariable String uid) {
        return routeService.getByUid(uid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#request.companyUid,'company','ROUTE.MANAGE')")
    public RouteDto create(@Valid @RequestBody CreateRouteRequest request) {
        return routeService.create(request);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'route','ROUTE.MANAGE')")
    public RouteDto update(@PathVariable String uid,
                           @Valid @RequestBody UpdateRouteRequest request) {
        return routeService.updateByUid(uid, request);
    }

    @PutMapping("/uid/{uid}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'route','ROUTE.MANAGE')")
    public void archive(@PathVariable String uid) {
        routeService.archiveByUid(uid);
    }

    @PutMapping("/uid/{uid}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'route','ROUTE.MANAGE')")
    public void restore(@PathVariable String uid) {
        routeService.restoreByUid(uid);
    }

    // -------------------------------------------------------------------------
    // Route ↔ Customer
    // -------------------------------------------------------------------------

    @GetMapping("/uid/{uid}/customers")
    @PreAuthorize("@perm.scoped(#uid,'route','ROUTE.VIEW')")
    public List<RouteCustomerDto> listCustomers(@PathVariable String uid) {
        return routeService.listCustomers(uid);
    }

    @PostMapping("/uid/{uid}/customers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'route','ROUTE.ASSIGN')")
    public RouteCustomerDto assignCustomer(@PathVariable String uid,
                                           @Valid @RequestBody AssignRouteCustomerRequest request) {
        return routeService.assignCustomer(uid, request);
    }

    @DeleteMapping("/uid/{uid}/customers/{customerUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'route','ROUTE.ASSIGN')")
    public void unassignCustomer(@PathVariable String uid, @PathVariable String customerUid) {
        routeService.unassignCustomer(uid, customerUid);
    }

    // -------------------------------------------------------------------------
    // Route ↔ Agent
    // -------------------------------------------------------------------------

    @GetMapping("/uid/{uid}/agents")
    @PreAuthorize("@perm.scoped(#uid,'route','ROUTE.VIEW')")
    public List<RouteAgentDto> listAgents(@PathVariable String uid) {
        return routeService.listAgents(uid);
    }

    @PostMapping("/uid/{uid}/agents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'route','ROUTE.ASSIGN')")
    public RouteAgentDto assignAgent(@PathVariable String uid,
                                     @Valid @RequestBody AssignRouteAgentRequest request) {
        return routeService.assignAgent(uid, request);
    }

    @DeleteMapping("/uid/{uid}/agents/{agentUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'route','ROUTE.ASSIGN')")
    public void unassignAgent(@PathVariable String uid, @PathVariable String agentUid) {
        routeService.unassignAgent(uid, agentUid);
    }

    // -------------------------------------------------------------------------
    // Route ↔ Branch
    // -------------------------------------------------------------------------

    @GetMapping("/uid/{uid}/branches")
    @PreAuthorize("@perm.scoped(#uid,'route','ROUTE.VIEW')")
    public List<RouteBranchDto> listBranches(@PathVariable String uid) {
        return routeService.listBranches(uid);
    }

    @PostMapping("/uid/{uid}/branches")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'route','ROUTE.MANAGE')")
    public RouteBranchDto assignBranch(@PathVariable String uid,
                                       @Valid @RequestBody AssignRouteBranchRequest request) {
        return routeService.assignBranch(uid, request);
    }

    @DeleteMapping("/uid/{uid}/branches/{branchUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'route','ROUTE.MANAGE')")
    public void unassignBranch(@PathVariable String uid, @PathVariable String branchUid) {
        routeService.unassignBranch(uid, branchUid);
    }
}
