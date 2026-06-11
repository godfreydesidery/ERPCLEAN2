package com.erp.api;

import com.erp.modules.documents.domain.dto.DocumentTemplateDto;
import com.erp.modules.documents.domain.dto.UpdateDocumentTemplateRequest;
import com.erp.modules.documents.service.DocumentTemplateService;
import com.erp.platform.security.RequestContext;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Document template registry admin endpoints (ADR-0023 D-11, FR-DOC-01/03).
 * Permission: DOCUMENT.TEMPLATE.MANAGE.
 */
@RestController
@RequestMapping("/api/v1/documents/templates")
public class DocumentTemplateController {

    private final DocumentTemplateService templateService;

    public DocumentTemplateController(DocumentTemplateService templateService) {
        this.templateService = templateService;
    }

    /**
     * List all registry rows for the active company.
     * GET /api/v1/documents/templates
     */
    @GetMapping
    @PreAuthorize("@perm.has('DOCUMENT.TEMPLATE.MANAGE')")
    public List<DocumentTemplateDto> list() {
        RequestContext.Principal principal = RequestContext.get();
        return templateService.listForCompany(principal != null ? principal.companyId() : null);
    }

    /**
     * Update a registry row (toggle active/inactive, set title/branding).
     * PUT /api/v1/documents/templates/{uid}
     */
    @PutMapping("/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'documenttemplate', 'DOCUMENT.TEMPLATE.MANAGE')")
    public DocumentTemplateDto update(@PathVariable String uid,
                                      @RequestBody UpdateDocumentTemplateRequest req) {
        return templateService.update(uid, req);
    }
}
