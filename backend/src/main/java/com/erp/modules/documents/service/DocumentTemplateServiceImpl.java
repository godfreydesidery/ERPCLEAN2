package com.erp.modules.documents.service;

import com.erp.modules.documents.domain.dto.DocumentTemplateDto;
import com.erp.modules.documents.domain.dto.UpdateDocumentTemplateRequest;
import com.erp.modules.documents.domain.entity.DocumentBranding;
import com.erp.modules.documents.domain.entity.DocumentTemplate;
import com.erp.modules.documents.repository.DocumentBrandingRepository;
import com.erp.modules.documents.repository.DocumentTemplateRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentTemplateServiceImpl implements DocumentTemplateService {

    private static final String DOCUMENT_TEMPLATE_UPDATE = "DOCUMENT.TEMPLATE.UPDATE";

    private final DocumentTemplateRepository templates;
    private final DocumentBrandingRepository brandings;
    private final AuditService               audit;

    public DocumentTemplateServiceImpl(DocumentTemplateRepository templates,
                                       DocumentBrandingRepository brandings,
                                       AuditService audit) {
        this.templates = templates;
        this.brandings = brandings;
        this.audit     = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentTemplateDto> listForCompany(Long companyId) {
        return templates.findByCompanyId(companyId)
                .stream()
                .map(DocumentTemplateDto::from)
                .toList();
    }

    @Override
    @Transactional
    public DocumentTemplateDto update(String uid, UpdateDocumentTemplateRequest req) {
        DocumentTemplate tmpl = templates.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Document template not found: " + uid));

        RequestContext.Principal principal = RequestContext.get();

        if (req.title() != null)  tmpl.setTitle(req.title());
        if (req.status() != null) {
            tmpl.setStatus(req.status());
            tmpl.setStatusChangedAt(Instant.now());
            tmpl.setStatusChangedBy(principal != null ? principal.userId() : null);
        }
        if (req.brandingId() != null) {
            // Ownership guard: the supplied branding must belong to the same company as the
            // template being edited. A foreign brandingId is silently rejected as "not found"
            // (404, no existence leak) per the error-hygiene rule (no raw ids in the message).
            DocumentBranding br = brandings
                    .findByIdAndCompanyId(req.brandingId(), tmpl.getCompanyId())
                    .orElseThrow(() -> new NotFoundException("Branding not found"));
            tmpl.setBrandingId(br.getId());
        }
        tmpl.setUpdatedAt(Instant.now());
        tmpl.setUpdatedBy(principal != null ? principal.userId() : null);

        DocumentTemplate saved = templates.save(tmpl);

        audit.record(AuditEvent.of(DOCUMENT_TEMPLATE_UPDATE, "document_templates",
                saved.getId(), saved.getUid())
                .detail(Map.of("documentType", saved.getDocumentType().name(),
                        "status", saved.getStatus().name())));

        return DocumentTemplateDto.from(saved);
    }
}
