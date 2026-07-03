package com.erp.api;

import com.erp.modules.sales.domain.dto.SalesSettingsDto;
import com.erp.modules.sales.domain.dto.UpdateSalesSettingsRequest;
import com.erp.modules.sales.service.SalesSettingsService;
import com.erp.platform.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sales Settings (SO approval threshold) REST controller (deferred item D-4, extends the PR #189
 * engine-derived sales-order approval flow). Mirrors {@link PurchaseSettingsController}.
 */
@RestController
@RequestMapping("/api/v1/sales-settings")
public class SalesSettingsController {

    private final SalesSettingsService service;

    public SalesSettingsController(SalesSettingsService service) {
        this.service = service;
    }

    @GetMapping("/by-company/{companyUid}")
    @PreAuthorize("@perm.scoped(#companyUid, 'company', 'SALES.SETTINGS.MANAGE')")
    public ApiResponse<SalesSettingsDto> getByCompany(@PathVariable String companyUid) {
        return ApiResponse.ok(service.getByCompanyUid(companyUid));
    }

    @PutMapping
    @PreAuthorize("@perm.scoped(#req.companyUid(), 'company', 'SALES.SETTINGS.MANAGE')")
    public ApiResponse<SalesSettingsDto> update(@Valid @RequestBody UpdateSalesSettingsRequest req) {
        return ApiResponse.ok(service.update(req));
    }
}
