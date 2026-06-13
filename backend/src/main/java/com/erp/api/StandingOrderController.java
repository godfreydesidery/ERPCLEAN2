package com.erp.api;

import com.erp.modules.sales.domain.dto.CreateStandingOrderRequest;
import com.erp.modules.sales.domain.dto.StandingOrderDto;
import com.erp.modules.sales.service.StandingOrderService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Standing (recurring) order lifecycle (ADR-0029 D-8).
 * Permissions seeded in V45__sales_blanket_standing.sql.
 */
@RestController
@RequestMapping("/api/v1/standing-orders")
public class StandingOrderController {

    private final StandingOrderService standingOrderService;

    public StandingOrderController(StandingOrderService standingOrderService) {
        this.standingOrderService = standingOrderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#request.companyUid(),'company','SALES.STANDING.CREATE')")
    public StandingOrderDto create(@Valid @RequestBody CreateStandingOrderRequest request) {
        return standingOrderService.createStanding(request);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'standingorder','SALES.STANDING.VIEW')")
    public StandingOrderDto getByUid(@PathVariable String uid) {
        return standingOrderService.getStandingByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('SALES.STANDING.VIEW')")
    public ApiResponse<List<StandingOrderDto>> list(@RequestParam Long companyId, Pageable pageable) {
        Page<StandingOrderDto> page = standingOrderService.listStandings(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @PostMapping("/uid/{uid}/pause")
    @PreAuthorize("@perm.scoped(#uid,'standingorder','SALES.STANDING.MANAGE')")
    public void pause(@PathVariable String uid) {
        standingOrderService.pauseStanding(uid);
    }

    @PostMapping("/uid/{uid}/resume")
    @PreAuthorize("@perm.scoped(#uid,'standingorder','SALES.STANDING.MANAGE')")
    public void resume(@PathVariable String uid) {
        standingOrderService.resumeStanding(uid);
    }

    @DeleteMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'standingorder','SALES.STANDING.MANAGE')")
    public void cancel(@PathVariable String uid) {
        standingOrderService.cancelStanding(uid);
    }

    @PostMapping("/uid/{uid}/trigger")
    @PreAuthorize("@perm.scoped(#uid,'standingorder','SALES.STANDING.MANAGE')")
    public StandingOrderDto triggerNow(@PathVariable String uid) {
        return standingOrderService.triggerNow(uid);
    }
}
