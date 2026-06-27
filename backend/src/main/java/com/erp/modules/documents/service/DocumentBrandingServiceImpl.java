package com.erp.modules.documents.service;

import com.erp.modules.documents.domain.dto.DocumentBrandingDto;
import com.erp.modules.documents.domain.dto.UpdateDocumentBrandingRequest;
import com.erp.modules.documents.domain.entity.DocumentBranding;
import com.erp.modules.documents.repository.DocumentBrandingRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentBrandingServiceImpl implements DocumentBrandingService {

    private static final String DOCUMENT_BRANDING_UPDATE = "DOCUMENT.BRANDING.UPDATE";

    private final DocumentBrandingRepository brandings;
    private final CompanyRepository          companies;
    private final AuditService               audit;

    public DocumentBrandingServiceImpl(DocumentBrandingRepository brandings,
                                        CompanyRepository companies,
                                        AuditService audit) {
        this.brandings = brandings;
        this.companies = companies;
        this.audit     = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentBrandingDto getForCompany(Long companyId) {
        return brandings.findByCompanyId(companyId)
                .map(DocumentBrandingDto::from)
                .orElseGet(() -> fallbackFromCompany(companyId));
    }

    @Override
    @Transactional
    public DocumentBrandingDto update(Long companyId, UpdateDocumentBrandingRequest req) {
        DocumentBranding branding = brandings.findByCompanyId(companyId)
                .orElseThrow(() -> new NotFoundException("Document branding not found for this company."));

        RequestContext.Principal principal = RequestContext.get();

        if (req.displayName() != null) branding.setDisplayName(req.displayName());
        if (req.legalName() != null)   branding.setLegalName(req.legalName());
        if (req.taxId() != null)        branding.setTaxId(req.taxId());
        if (req.addressLine1() != null) branding.setAddressLine1(req.addressLine1());
        if (req.addressLine2() != null) branding.setAddressLine2(req.addressLine2());
        if (req.city() != null)         branding.setCity(req.city());
        if (req.region() != null)       branding.setRegion(req.region());
        if (req.country() != null)      branding.setCountry(req.country());
        if (req.postalCode() != null)   branding.setPostalCode(req.postalCode());
        if (req.contactPhone() != null) branding.setContactPhone(req.contactPhone());
        if (req.contactEmail() != null) branding.setContactEmail(req.contactEmail());
        if (req.website() != null)      branding.setWebsite(req.website());
        if (req.logoRef() != null)      branding.setLogoRef(req.logoRef());
        if (req.logoDataUri() != null)  branding.setLogoDataUri(normaliseLogo(req.logoDataUri()));
        if (req.footerTerms() != null)  branding.setFooterTerms(req.footerTerms());
        if (req.bankDetails() != null)  branding.setBankDetails(req.bankDetails());

        branding.setUpdatedAt(Instant.now());
        branding.setUpdatedBy(principal != null ? principal.userId() : null);

        DocumentBranding saved = brandings.save(branding);

        audit.record(AuditEvent.of(DOCUMENT_BRANDING_UPDATE, "document_branding",
                saved.getId(), saved.getUid())
                .detail(Map.of("companyId", String.valueOf(companyId))));

        return DocumentBrandingDto.from(saved);
    }

    // -------------------------------------------------------------------------

    /** Fallback: build a minimal branding DTO from the companies row (BR-DOC-06). */
    private DocumentBrandingDto fallbackFromCompany(Long companyId) {
        Company company = companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found."));
        return new DocumentBrandingDto(
                null, null, companyId,
                company.getName(), company.getLegalName(), company.getTaxId(),
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    /**
     * Validates and normalises an inline logo data URI. Blank clears the logo (returns null);
     * otherwise it must be a PNG or JPEG base64 data URI (the only formats the PDF renderer embeds).
     * Throws a friendly 400 on anything else. The ~70 KB size cap is enforced by the request
     * {@code @Size} and the DB CHECK constraint (V75).
     */
    private static String normaliseLogo(String dataUri) {
        String v = dataUri.trim();
        if (v.isEmpty()) {
            return null;
        }
        String lower = v.toLowerCase();
        if (!lower.startsWith("data:image/png;base64,") && !lower.startsWith("data:image/jpeg;base64,")) {
            throw new IllegalArgumentException("Logo must be a PNG or JPEG image.");
        }
        return v;
    }
}
