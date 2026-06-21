package com.erp.api;

import com.erp.modules.purchases.domain.dto.PurchaseSettingsDto;
import com.erp.modules.purchases.domain.dto.UpdatePurchaseSettingsRequest;
import com.erp.modules.purchases.service.PurchaseSettingsService;
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
 * Purchase Settings (PO approval threshold) REST controller (ADR-0027 D-6).
 */
@RestController
@RequestMapping("/api/v1/purchase-settings")
public class PurchaseSettingsController {

    private final PurchaseSettingsService service;

    public PurchaseSettingsController(PurchaseSettingsService service) {
        this.service = service;
    }

    @GetMapping("/by-company/{companyUid}")
    @PreAuthorize("@perm.scoped(#companyUid, 'company', 'PURCHASE.SETTINGS.VIEW')")
    public ApiResponse<PurchaseSettingsDto> getByCompany(@PathVariable String companyUid) {
        return ApiResponse.ok(service.getByCompanyUid(companyUid));
    }

    @PutMapping
    @PreAuthorize("@perm.scoped(#req.companyUid(), 'company', 'PURCHASE.SETTINGS.EDIT')")
    public ApiResponse<PurchaseSettingsDto> update(@Valid @RequestBody UpdatePurchaseSettingsRequest req) {
        return ApiResponse.ok(service.update(req));
    }
}
