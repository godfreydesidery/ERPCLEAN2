package com.erp.api;

import com.erp.modules.purchases.domain.dto.CreateLandedCostRequest;
import com.erp.modules.purchases.domain.dto.LandedCostDto;
import com.erp.modules.purchases.service.LandedCostService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Landed Cost REST controller (ADR-0027 D-5, FR-PROC-13..16).
 */
@RestController
@RequestMapping("/api/v1/landed-costs")
public class LandedCostController {

    private final LandedCostService service;

    public LandedCostController(LandedCostService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#req.companyUid(), 'company', 'PURCHASE.LANDEDCOST.MANAGE')")
    public ApiResponse<LandedCostDto> create(@Valid @RequestBody CreateLandedCostRequest req) {
        return ApiResponse.ok(service.create(req));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'landedcost', 'PURCHASE.LANDEDCOST.VIEW')")
    public ApiResponse<LandedCostDto> getByUid(@PathVariable String uid) {
        return ApiResponse.ok(service.getByUid(uid));
    }

    @GetMapping
    @PreAuthorize("@perm.has('PURCHASE.LANDEDCOST.VIEW')")
    public ApiResponse<List<LandedCostDto>> list(
            @RequestParam Long companyId,
            Pageable pageable) {
        Page<LandedCostDto> page = service.list(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /** Confirm: DRAFT → CONFIRMED; allocates charges to GR lines; publishes outbox event. */
    @PostMapping("/uid/{uid}/confirm")
    @PreAuthorize("@perm.scoped(#uid, 'landedcost', 'PURCHASE.LANDEDCOST.MANAGE')")
    public ApiResponse<LandedCostDto> confirm(@PathVariable String uid) {
        return ApiResponse.ok(service.confirm(uid));
    }
}
