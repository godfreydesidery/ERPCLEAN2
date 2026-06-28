package com.erp.api;

import com.erp.modules.tax.domain.dto.CreateWhtTypeRequest;
import com.erp.modules.tax.domain.dto.UpdateWhtTypeRequest;
import com.erp.modules.tax.domain.dto.WhtTypeDto;
import com.erp.modules.tax.service.WhtTypeService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * WHT type (rate master) CRUD (ADR-0017 D-2d, FR-WHT-01/02/03).
 * Permissions seeded in V14: WHT.MANAGE, WHT.VIEW.
 */
@RestController
@RequestMapping("/api/v1/wht/types")
public class WhtTypeController {

    private final WhtTypeService service;

    public WhtTypeController(WhtTypeService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#req.companyUid,'company','WHT.MANAGE')")
    public WhtTypeDto create(@Valid @RequestBody CreateWhtTypeRequest req) {
        return service.create(req);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'whttype','WHT.MANAGE')")
    public WhtTypeDto update(@PathVariable String uid,
                              @Valid @RequestBody UpdateWhtTypeRequest req) {
        return service.update(uid, req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'whttype','WHT.VIEW')")
    public WhtTypeDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    // WHT-type list feeds the withholding-tax dropdown on cash/AR/AP capture screens. It is
    // low-sensitivity, company-scoped rate reference data; allow WHT.VIEW holders OR any scoped member
    // of the active company so the dropdown never hard-403s a finance/cashier capture form (sim
    // 2026-06-28, ISSUE-001). The service applies its own company-scope predicate, so this is a
    // within-tenant read widening only.
    @GetMapping
    @PreAuthorize("@perm.hasOrMember('WHT.VIEW')")
    public List<WhtTypeDto> listByCompany(@RequestParam Long companyId) {
        return service.listByCompany(companyId);
    }

    @PostMapping("/uid/{uid}/deactivate")
    @PreAuthorize("@perm.scoped(#uid,'whttype','WHT.MANAGE')")
    public WhtTypeDto deactivate(@PathVariable String uid) {
        return service.deactivate(uid);
    }
}
