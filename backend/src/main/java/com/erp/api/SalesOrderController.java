package com.erp.api;

import com.erp.modules.sales.domain.dto.AddSalesOrderLineRequest;
import com.erp.modules.sales.domain.dto.CancelSalesOrderRequest;
import com.erp.modules.sales.domain.dto.CreateSalesOrderRequest;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import com.erp.modules.sales.domain.dto.SalesOrderLineDto;
import com.erp.modules.sales.domain.dto.SetSalesOrderAgentRequest;
import com.erp.modules.sales.service.SalesOrderService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sales order lifecycle REST controller (ADR-0021 D-3/D-4/D-5).
 * Responses auto-wrapped by ApiResponseAdvice.
 * Permission codes seeded in V18__sales_orders.sql.
 */
@RestController
@RequestMapping("/api/v1/sales-orders")
public class SalesOrderController {

    private final SalesOrderService salesOrderService;

    public SalesOrderController(SalesOrderService salesOrderService) {
        this.salesOrderService = salesOrderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#request.companyUid(),'company','SALES.ORDER.CREATE')")
    public SalesOrderDto create(@Valid @RequestBody CreateSalesOrderRequest request) {
        return salesOrderService.create(request);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'salesorder','SALES.ORDER.VIEW')")
    public SalesOrderDto getByUid(@PathVariable String uid) {
        return salesOrderService.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('SALES.ORDER.VIEW')")
    public ApiResponse<List<SalesOrderDto>> list(@RequestParam Long companyId,
                                                  Pageable pageable) {
        Page<SalesOrderDto> page = salesOrderService.list(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    // -------------------------------------------------------------------------
    // Lines
    // -------------------------------------------------------------------------

    @GetMapping("/uid/{uid}/lines")
    @PreAuthorize("@perm.scoped(#uid,'salesorder','SALES.ORDER.VIEW')")
    public List<SalesOrderLineDto> listLines(@PathVariable String uid) {
        return salesOrderService.listLines(uid);
    }

    @PostMapping("/uid/{uid}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'salesorder','SALES.ORDER.CREATE')")
    public SalesOrderLineDto addLine(@PathVariable String uid,
                                     @Valid @RequestBody AddSalesOrderLineRequest request) {
        return salesOrderService.addLine(uid, request);
    }

    @DeleteMapping("/uid/{uid}/lines/{lineUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'salesorder','SALES.ORDER.CREATE')")
    public void removeLine(@PathVariable String uid, @PathVariable String lineUid) {
        salesOrderService.removeLine(uid, lineUid);
    }

    // -------------------------------------------------------------------------
    // Lifecycle transitions
    // -------------------------------------------------------------------------

    @PutMapping("/uid/{uid}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'salesorder','SALES.ORDER.CONFIRM')")
    public void confirm(@PathVariable String uid) {
        salesOrderService.confirm(uid);
    }

    @PutMapping("/uid/{uid}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'salesorder','SALES.ORDER.CANCEL')")
    public void cancel(@PathVariable String uid,
                       @RequestBody(required = false) CancelSalesOrderRequest request) {
        salesOrderService.cancel(uid, request);
    }

    /**
     * Sets or changes the order's sales agent so an agentless (or wrong-agent) order can be
     * invoiced. Allowed only in pre-invoice states; reuses the SALES.ORDER.CREATE permission.
     */
    @PatchMapping("/uid/{uid}/agent")
    @PreAuthorize("@perm.scoped(#uid,'salesorder','SALES.ORDER.CREATE')")
    public SalesOrderDto setAgent(@PathVariable String uid,
                                  @Valid @RequestBody SetSalesOrderAgentRequest request) {
        return salesOrderService.setAgent(uid, request.agentUid());
    }
}
