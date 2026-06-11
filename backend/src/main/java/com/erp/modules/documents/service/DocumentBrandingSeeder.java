package com.erp.modules.documents.service;

import com.erp.modules.documents.domain.entity.DocumentBranding;
import com.erp.modules.documents.domain.entity.DocumentTemplate;
import com.erp.modules.documents.domain.enums.DocumentType;
import com.erp.modules.documents.domain.enums.RendererKey;
import com.erp.modules.documents.repository.DocumentBrandingRepository;
import com.erp.modules.documents.repository.DocumentTemplateRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a document_branding row + the six v1 document_templates rows for a new company
 * (ADR-0023 D-10 / FR-DOC-02). Called by BootstrapRunner and CompanyService.create (if present).
 * Idempotent — skips if rows already exist (the V24 migration backfills existing companies via SQL).
 */
@Component
public class DocumentBrandingSeeder {

    private static final Logger log = LoggerFactory.getLogger(DocumentBrandingSeeder.class);

    /** v1 document types, their renderer keys, and default titles. */
    private static final Map<DocumentType, String[]> V1_TYPES = Map.of(
            DocumentType.INVOICE,         new String[]{"TRANSACTIONAL_PDF", "TAX INVOICE"},
            DocumentType.AR_STATEMENT,    new String[]{"STATEMENT_PDF",     "CUSTOMER STATEMENT"},
            DocumentType.PURCHASE_ORDER,  new String[]{"TRANSACTIONAL_PDF", "PURCHASE ORDER"},
            DocumentType.GOODS_RECEIPT,   new String[]{"TRANSACTIONAL_PDF", "GOODS RECEIVED NOTE"},
            DocumentType.DELIVERY_NOTE,   new String[]{"TRANSACTIONAL_PDF", "DELIVERY NOTE"},
            DocumentType.CREDIT_NOTE,     new String[]{"TRANSACTIONAL_PDF", "CREDIT NOTE"}
    );

    private final DocumentBrandingRepository  brandings;
    private final DocumentTemplateRepository  templates;
    private final CompanyRepository           companies;

    public DocumentBrandingSeeder(DocumentBrandingRepository brandings,
                                   DocumentTemplateRepository templates,
                                   CompanyRepository companies) {
        this.brandings = brandings;
        this.templates = templates;
        this.companies = companies;
    }

    @Transactional
    public void seedDefaults(Long companyId) {
        // --- Branding ---
        if (brandings.findByCompanyId(companyId).isEmpty()) {
            Company company = companies.findById(companyId)
                    .orElseThrow(() -> new IllegalStateException("Company not found: " + companyId));
            DocumentBranding branding = new DocumentBranding(companyId, company.getName());
            branding.setLegalName(company.getLegalName());
            branding.setTaxId(company.getTaxId());
            brandings.save(branding);
            log.info("DocumentBrandingSeeder: seeded branding for company {}.", companyId);
        }

        // --- Templates ---
        for (Map.Entry<DocumentType, String[]> entry : V1_TYPES.entrySet()) {
            DocumentType type = entry.getKey();
            String[] meta = entry.getValue();
            if (templates.findByCompanyIdAndDocumentType(companyId, type).isEmpty()) {
                RendererKey key = RendererKey.valueOf(meta[0]);
                DocumentTemplate tmpl = new DocumentTemplate(companyId, type, key, meta[1]);
                templates.save(tmpl);
                log.info("DocumentBrandingSeeder: seeded template {} for company {}.", type, companyId);
            }
        }
    }
}
