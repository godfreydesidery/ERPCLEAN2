package com.erp.api;

import com.erp.modules.hr.domain.dto.CreatePayeBandSetRequest;
import com.erp.modules.hr.domain.dto.CreateStatutoryRateSetRequest;
import com.erp.modules.hr.domain.dto.PayeBandSetDto;
import com.erp.modules.hr.domain.dto.StatutoryRateSetDto;
import com.erp.modules.hr.service.StatutoryRateService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.validation.Valid;
import java.util.List;
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

@RestController
@RequestMapping("/api/v1/hr/statutory")
public class HrStatutoryController {

    private final StatutoryRateService service;
    private final ScopeGuard           scopeGuard;

    public HrStatutoryController(StatutoryRateService service, ScopeGuard scopeGuard) {
        this.service    = service;
        this.scopeGuard = scopeGuard;
    }

    // --- PAYE band sets ---

    @GetMapping("/paye-bands")
    @PreAuthorize("@perm.has('HR.STATUTORY.MANAGE')")
    public ApiResponse<List<PayeBandSetDto>> listPayeBandSets(@RequestParam Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return ApiResponse.ok(service.listPayeBandSets(companyId));
    }

    @PostMapping("/paye-bands")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('HR.STATUTORY.MANAGE')")
    public PayeBandSetDto createPayeBandSet(@Valid @RequestBody CreatePayeBandSetRequest req) {
        return service.createPayeBandSet(req);
    }

    @GetMapping("/paye-bands/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'payebandset','HR.STATUTORY.MANAGE')")
    public PayeBandSetDto getPayeBandSet(@PathVariable String uid) {
        return service.getPayeBandSetByUid(uid);
    }

    // --- Other statutory rate sets ---

    @GetMapping("/rates")
    @PreAuthorize("@perm.has('HR.STATUTORY.MANAGE')")
    public ApiResponse<List<StatutoryRateSetDto>> listRateSets(@RequestParam Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return ApiResponse.ok(service.listRateSets(companyId));
    }

    @PostMapping("/rates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('HR.STATUTORY.MANAGE')")
    public StatutoryRateSetDto createRateSet(@Valid @RequestBody CreateStatutoryRateSetRequest req) {
        return service.createRateSet(req);
    }

    @GetMapping("/rates/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'statutoryrateset','HR.STATUTORY.MANAGE')")
    public StatutoryRateSetDto getRateSet(@PathVariable String uid) {
        return service.getRateSetByUid(uid);
    }
}
