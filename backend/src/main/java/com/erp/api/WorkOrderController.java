package com.erp.api;

import com.erp.modules.manufacturing.domain.dto.AddOperationRequest;
import com.erp.modules.manufacturing.domain.dto.ApplyCostRequest;
import com.erp.modules.manufacturing.domain.dto.CloseWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.CompleteWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.CreateWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.IssueComponentsRequest;
import com.erp.modules.manufacturing.domain.dto.ReleaseWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.UpdateWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.WorkOrderCostReportDto;
import com.erp.modules.manufacturing.domain.dto.WorkOrderDto;
import com.erp.modules.manufacturing.domain.dto.WorkOrderOperationDto;
import com.erp.modules.manufacturing.domain.enums.WorkOrderStatus;
import com.erp.modules.manufacturing.service.WorkOrderCostingService;
import com.erp.modules.manufacturing.service.WorkOrderService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
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
 * Work order REST controller (ADR-0035 D-1).
 *
 * <p>All responses auto-wrapped in {@code ApiResponse<T>} by {@code ApiResponseAdvice}.
 * Permission gates: {@code @perm.has} / {@code @perm.scoped} — NEVER {@code hasAuthority}.
 *
 * <p>URL layout:
 * <ul>
 *   <li>{@code GET    /api/v1/work-orders}                             — list (paged)</li>
 *   <li>{@code GET    /api/v1/work-orders/uid/{uid}}                   — detail + lines</li>
 *   <li>{@code POST   /api/v1/work-orders}                             — create</li>
 *   <li>{@code PUT    /api/v1/work-orders/uid/{uid}}                   — update (PLANNED only)</li>
 *   <li>{@code POST   /api/v1/work-orders/uid/{uid}/release}           — PLANNED→RELEASED</li>
 *   <li>{@code POST   /api/v1/work-orders/uid/{uid}/cancel}            — cancel</li>
 *   <li>{@code POST   /api/v1/work-orders/uid/{uid}/issue-components}  — issue stock into WIP</li>
 *   <li>{@code POST   /api/v1/work-orders/uid/{uid}/apply-cost}        — apply labour/overhead</li>
 *   <li>{@code POST   /api/v1/work-orders/uid/{uid}/complete}          — FG receipt + WIP relief</li>
 *   <li>{@code POST   /api/v1/work-orders/uid/{uid}/close}             — variance clear + CLOSED</li>
 *   <li>{@code GET    /api/v1/work-orders/uid/{uid}/cost-report}       — cost report</li>
 *   <li>{@code POST   /api/v1/work-orders/uid/{uid}/operations}        — add operation</li>
 *   <li>{@code DELETE /api/v1/work-orders/uid/{uid}/operations/{opUid}} — remove operation</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/work-orders")
public class WorkOrderController {

    private final WorkOrderService        workOrderService;
    private final WorkOrderCostingService costingService;
    private final ScopeGuard              scopeGuard;

    public WorkOrderController(WorkOrderService workOrderService,
                                WorkOrderCostingService costingService,
                                ScopeGuard scopeGuard) {
        this.workOrderService = workOrderService;
        this.costingService   = costingService;
        this.scopeGuard       = scopeGuard;
    }

    // -------------------------------------------------------------------------
    // List + detail
    // -------------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("@perm.has('MANUFACTURING.VIEW')")
    public ApiResponse<List<WorkOrderDto>> list(
            @RequestParam Long companyId,
            @RequestParam(required = false) WorkOrderStatus status,
            Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Page<WorkOrderDto> page = workOrderService.list(companyId, status, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'workorder','MANUFACTURING.VIEW')")
    public ApiResponse<WorkOrderDto> getByUid(@PathVariable String uid) {
        return ApiResponse.ok(workOrderService.getByUid(uid));
    }

    // -------------------------------------------------------------------------
    // Create / update
    // -------------------------------------------------------------------------

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('WORKORDER.MANAGE')")
    public ApiResponse<WorkOrderDto> create(@RequestBody @Valid CreateWorkOrderRequest req) {
        return ApiResponse.ok(workOrderService.create(req));
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'workorder','WORKORDER.MANAGE')")
    public ApiResponse<WorkOrderDto> update(@PathVariable String uid,
                                             @RequestBody @Valid UpdateWorkOrderRequest req) {
        return ApiResponse.ok(workOrderService.update(uid, req));
    }

    // -------------------------------------------------------------------------
    // Lifecycle transitions
    // -------------------------------------------------------------------------

    @PostMapping("/uid/{uid}/release")
    @PreAuthorize("@perm.scoped(#uid,'workorder','WORKORDER.RELEASE')")
    public ApiResponse<WorkOrderDto> release(@PathVariable String uid,
                                              @RequestBody(required = false) ReleaseWorkOrderRequest req) {
        return ApiResponse.ok(workOrderService.release(uid, req));
    }

    @PostMapping("/uid/{uid}/cancel")
    @PreAuthorize("@perm.scoped(#uid,'workorder','WORKORDER.MANAGE')")
    public ApiResponse<WorkOrderDto> cancel(@PathVariable String uid,
                                             @RequestParam(required = false) String reason) {
        return ApiResponse.ok(workOrderService.cancel(uid, reason));
    }

    // -------------------------------------------------------------------------
    // Costing operations
    // -------------------------------------------------------------------------

    @PostMapping("/uid/{uid}/issue-components")
    @PreAuthorize("@perm.scoped(#uid,'workorder','WORKORDER.MANAGE')")
    public ApiResponse<WorkOrderDto> issueComponents(@PathVariable String uid,
                                                      @RequestBody @Valid IssueComponentsRequest req) {
        return ApiResponse.ok(costingService.issueComponents(uid, req));
    }

    @PostMapping("/uid/{uid}/apply-cost")
    @PreAuthorize("@perm.scoped(#uid,'workorder','WORKORDER.MANAGE')")
    public ApiResponse<WorkOrderDto> applyCost(@PathVariable String uid,
                                                @RequestBody @Valid ApplyCostRequest req) {
        return ApiResponse.ok(costingService.applyCost(uid, req));
    }

    @PostMapping("/uid/{uid}/complete")
    @PreAuthorize("@perm.scoped(#uid,'workorder','WORKORDER.CLOSE')")
    public ApiResponse<WorkOrderDto> complete(@PathVariable String uid,
                                               @RequestBody @Valid CompleteWorkOrderRequest req) {
        return ApiResponse.ok(costingService.complete(uid, req));
    }

    @PostMapping("/uid/{uid}/close")
    @PreAuthorize("@perm.scoped(#uid,'workorder','WORKORDER.CLOSE')")
    public ApiResponse<WorkOrderDto> close(@PathVariable String uid,
                                            @RequestBody @Valid CloseWorkOrderRequest req) {
        return ApiResponse.ok(costingService.close(uid, req));
    }

    @GetMapping("/uid/{uid}/cost-report")
    @PreAuthorize("@perm.scoped(#uid,'workorder','MANUFACTURING.VIEW')")
    public ApiResponse<WorkOrderCostReportDto> costReport(@PathVariable String uid) {
        return ApiResponse.ok(costingService.costReport(uid));
    }

    // -------------------------------------------------------------------------
    // Operations sub-resource
    // -------------------------------------------------------------------------

    @PostMapping("/uid/{uid}/operations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'workorder','WORKORDER.MANAGE')")
    public ApiResponse<WorkOrderOperationDto> addOperation(@PathVariable String uid,
                                                            @RequestBody @Valid AddOperationRequest req) {
        return ApiResponse.ok(workOrderService.addOperation(uid, req));
    }

    @DeleteMapping("/uid/{uid}/operations/{opUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'workorder','WORKORDER.MANAGE')")
    public void removeOperation(@PathVariable String uid, @PathVariable String opUid) {
        workOrderService.removeOperation(uid, opUid);
    }
}
