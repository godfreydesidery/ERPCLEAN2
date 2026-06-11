package com.erp.modules.documents.service;

import com.erp.modules.documents.domain.dto.DocumentTemplateDto;
import com.erp.modules.documents.domain.dto.UpdateDocumentTemplateRequest;
import java.util.List;

/**
 * Manages the per-company document template registry (ADR-0023 D-3, FR-DOC-01/03).
 */
public interface DocumentTemplateService {

    /** Returns all registry rows for the active company. */
    List<DocumentTemplateDto> listForCompany(Long companyId);

    /** Updates a registry row (toggle active/inactive, set title/branding); audited. */
    DocumentTemplateDto update(String uid, UpdateDocumentTemplateRequest req);
}
