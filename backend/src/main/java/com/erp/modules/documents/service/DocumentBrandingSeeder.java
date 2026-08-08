package com.erp.modules.documents.service;

import com.erp.modules.documents.domain.entity.DocumentBranding;
import com.erp.modules.documents.domain.entity.DocumentTemplate;
import com.erp.modules.documents.domain.enums.DocumentType;
import com.erp.modules.documents.domain.enums.RendererKey;
import com.erp.modules.documents.repository.DocumentBrandingRepository;
import com.erp.modules.documents.repository.DocumentTemplateRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.common.domain.MasterStatus;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a document_branding row + the renderable document_templates rows for a company
 * (ADR-0023 D-10 / FR-DOC-02). Called by BootstrapRunner and CompanyProvisioningServiceImpl.
 * Idempotent — skips if rows already exist (the V24 migration backfills existing companies via SQL).
 *
 * <p><b>Adding a type here heals existing tenants without a migration.</b> The per-type check below
 * is per row, not "has this company any templates at all", so a company provisioned before a type
 * was added picks it up: automatically at bootstrap, automatically again on every application start
 * via {@link #healMissingDefaults()}, and on demand via
 * {@code POST /api/v1/companies/uid/{uid}/provision-defaults}
 * ({@code CompanyServiceImpl.reprovisionDefaults} → {@code provisionDefaults} → here). That is the
 * standing "provisioning over data migrations" rule: per-tenant reference data is seeded by app
 * code, never backfilled by Flyway.
 */
@Component
public class DocumentBrandingSeeder {

    private static final Logger log = LoggerFactory.getLogger(DocumentBrandingSeeder.class);

    /**
     * Renderable document types, their renderer keys, and default titles.
     *
     * <p>QUOTATION is titled <b>PROFORMA INVOICE</b>: the document a customer is handed before they
     * commit is universally called a proforma, and that is what was asked for. The type stays
     * QUOTATION because both V19 CHECK constraints already permit that name — a distinct PROFORMA
     * type would need a migration and buy nothing. The title lives in the per-company template row,
     * so a tenant that prefers "QUOTATION" can rename it on the Document Templates screen without
     * a code change.
     */
    private static final String TXN  = RendererKey.TRANSACTIONAL_PDF.name();
    private static final String STMT = RendererKey.STATEMENT_PDF.name();

    private static final Map<DocumentType, String[]> V1_TYPES = Map.of(
            DocumentType.INVOICE,         new String[]{TXN,  "TAX INVOICE"},
            DocumentType.AR_STATEMENT,    new String[]{STMT, "CUSTOMER STATEMENT"},
            DocumentType.PURCHASE_ORDER,  new String[]{TXN,  "PURCHASE ORDER"},
            DocumentType.GOODS_RECEIPT,   new String[]{TXN,  "GOODS RECEIVED NOTE"},
            DocumentType.DELIVERY_NOTE,   new String[]{TXN,  "DELIVERY NOTE"},
            DocumentType.CREDIT_NOTE,     new String[]{TXN,  "CREDIT NOTE"},
            DocumentType.QUOTATION,       new String[]{TXN,  "PROFORMA INVOICE"}
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
                    .orElseThrow(() -> new IllegalStateException("Company not found."));
            seedBranding(company);
        }

        // --- Templates ---
        for (Map.Entry<DocumentType, String[]> entry : V1_TYPES.entrySet()) {
            seedTemplateIfMissing(companyId, entry.getKey(), entry.getValue());
        }
    }

    // -------------------------------------------------------------------------
    // Self-heal for tenants that predate a document type (UAT finding #6)
    // -------------------------------------------------------------------------

    /**
     * Seeds any default branding/template row an existing company is missing, once the application
     * is up.
     *
     * <p><b>The defect this closes.</b> {@link #seedDefaults} only ever ran when a company was
     * <em>created</em> or when an administrator hit
     * {@code POST /api/v1/companies/uid/{uid}/provision-defaults}, which is gated on
     * {@code COMPANY.MANAGE} — a permission no sales role holds. So when a new renderable type was
     * added (QUOTATION, titled "PROFORMA INVOICE"), every company provisioned before it had no such
     * template, and the proforma button failed for its users with "Document templates aren't set up
     * for this company yet" until somebody with admin rights ran a step nobody had told them about.
     * Companies 3, 4 and 5 in the UAT database were in exactly that state. Healing here means an
     * upgrade fixes itself: the missing rows appear on the first boot of the new build, before any
     * user touches the feature.
     *
     * <p>This is the standing "provisioning over data migrations" rule — per-tenant reference data
     * is created by app code, never backfilled by Flyway. It is also why nothing here needs a
     * migration.
     *
     * <p><b>Idempotent and concurrency-safe.</b> Every row is checked before it is written, so a
     * second boot creates nothing. Two instances booting at the same moment can still both pass the
     * check; the loser's insert hits {@code uq_document_template_company_type} (V19) and is caught
     * and ignored — the row it wanted exists. Deliberately NOT transactional: each save commits on
     * its own, so one company's failure (or one lost race) can neither roll back nor poison the
     * healing of the rest. ARCHIVED companies are skipped — they render nothing.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void healMissingDefaults() {
        int created = 0;
        List<Company> all;
        try {
            all = companies.findAll();
        } catch (RuntimeException ex) {
            // Never let a self-heal stop the application from serving traffic.
            log.warn("DocumentBrandingSeeder: could not list companies to heal document defaults.", ex);
            return;
        }
        for (Company company : all) {
            if (company.getStatus() == MasterStatus.ARCHIVED) {
                continue;
            }
            created += healCompany(company);
        }
        if (created > 0) {
            log.info("DocumentBrandingSeeder: healed {} missing document default row(s) across {} "
                    + "company/companies.", created, all.size());
        }
    }

    /** Heals one company; returns how many rows it had to create. Never throws. */
    private int healCompany(Company company) {
        int created = 0;
        Long companyId = company.getId();
        try {
            if (brandings.findByCompanyId(companyId).isEmpty()) {
                seedBranding(company);
                created++;
            }
        } catch (DataIntegrityViolationException ex) {
            log.debug("DocumentBrandingSeeder: branding for company {} was created concurrently.",
                    companyId);
        } catch (RuntimeException ex) {
            log.warn("DocumentBrandingSeeder: could not heal branding for company {}.", companyId, ex);
        }
        for (Map.Entry<DocumentType, String[]> entry : V1_TYPES.entrySet()) {
            DocumentType type = entry.getKey();
            try {
                if (seedTemplateIfMissing(companyId, type, entry.getValue())) {
                    created++;
                }
            } catch (DataIntegrityViolationException ex) {
                log.debug("DocumentBrandingSeeder: template {} for company {} was created "
                        + "concurrently.", type, companyId);
            } catch (RuntimeException ex) {
                log.warn("DocumentBrandingSeeder: could not heal template {} for company {}.",
                        type, companyId, ex);
            }
        }
        return created;
    }

    // -------------------------------------------------------------------------
    // Row writers — shared by provisioning and healing so the two cannot drift
    // -------------------------------------------------------------------------

    private void seedBranding(Company company) {
        DocumentBranding branding = new DocumentBranding(company.getId(), company.getName());
        branding.setLegalName(company.getLegalName());
        branding.setTaxId(company.getTaxId());
        brandings.save(branding);
        log.info("DocumentBrandingSeeder: seeded branding for company {}.", company.getId());
    }

    /** @return true when the template was missing and has been created. */
    private boolean seedTemplateIfMissing(Long companyId, DocumentType type, String[] meta) {
        if (templates.findByCompanyIdAndDocumentType(companyId, type).isPresent()) {
            return false;
        }
        RendererKey key = RendererKey.valueOf(meta[0]);
        templates.save(new DocumentTemplate(companyId, type, key, meta[1]));
        log.info("DocumentBrandingSeeder: seeded template {} for company {}.", type, companyId);
        return true;
    }
}
