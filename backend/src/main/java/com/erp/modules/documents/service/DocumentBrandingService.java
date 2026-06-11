package com.erp.modules.documents.service;

import com.erp.modules.documents.domain.dto.DocumentBrandingDto;
import com.erp.modules.documents.domain.dto.UpdateDocumentBrandingRequest;

/**
 * Manages the per-company branding profile (ADR-0023 D-2, FR-DOC-04/05).
 */
public interface DocumentBrandingService {

    /** Returns the branding profile for the active company; creates a default one if absent. */
    DocumentBrandingDto getForCompany(Long companyId);

    /** Updates the branding profile; audited (FR-DOC-05). */
    DocumentBrandingDto update(Long companyId, UpdateDocumentBrandingRequest req);
}
