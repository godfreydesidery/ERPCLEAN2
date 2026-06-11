package com.erp.api;

import com.erp.modules.documents.domain.dto.DocumentBrandingDto;
import com.erp.modules.documents.domain.dto.UpdateDocumentBrandingRequest;
import com.erp.modules.documents.service.DocumentBrandingService;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Company branding profile admin endpoints (ADR-0023 D-11, FR-DOC-04/05).
 * Permission: DOCUMENT.BRANDING.MANAGE.
 */
@RestController
@RequestMapping("/api/v1/documents/branding")
public class DocumentBrandingController {

    private final DocumentBrandingService brandingService;
    private final ScopeGuard              scopeGuard;

    public DocumentBrandingController(DocumentBrandingService brandingService, ScopeGuard scopeGuard) {
        this.brandingService = brandingService;
        this.scopeGuard      = scopeGuard;
    }

    /**
     * Get the active company's branding profile.
     * GET /api/v1/documents/branding
     */
    @GetMapping
    @PreAuthorize("@perm.has('DOCUMENT.BRANDING.MANAGE')")
    public DocumentBrandingDto get() {
        RequestContext.Principal principal = RequestContext.get();
        return brandingService.getForCompany(principal != null ? principal.companyId() : null);
    }

    /**
     * Update the active company's branding profile.
     * PUT /api/v1/documents/branding
     */
    @PutMapping
    @PreAuthorize("@perm.has('DOCUMENT.BRANDING.MANAGE')")
    public DocumentBrandingDto update(@RequestBody UpdateDocumentBrandingRequest req) {
        RequestContext.Principal principal = RequestContext.get();
        Long companyId = principal != null ? principal.companyId() : null;
        scopeGuard.assertCanActIn(principal, companyId);
        return brandingService.update(companyId, req);
    }
}
