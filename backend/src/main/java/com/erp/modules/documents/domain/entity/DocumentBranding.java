package com.erp.modules.documents.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-company branding profile for document headers and footers (ADR-0023 D-2).
 * One row per company (uq_document_branding_company). Seeded from companies.name/legal_name/tax_id.
 * Never alters the frozen companies table.
 */
@Getter
@Setter
@Entity
@Table(name = "document_branding")
public class DocumentBranding extends UidEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Column(name = "legal_name", length = 200)
    private String legalName;

    @Column(name = "tax_id", length = 60)
    private String taxId;

    @Column(name = "address_line1", length = 200)
    private String addressLine1;

    @Column(name = "address_line2", length = 200)
    private String addressLine2;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "region", length = 120)
    private String region;

    @Column(name = "country", length = 120)
    private String country;

    @Column(name = "postal_code", length = 40)
    private String postalCode;

    @Column(name = "contact_phone", length = 160)
    private String contactPhone;

    @Column(name = "contact_email", length = 160)
    private String contactEmail;

    @Column(name = "website", length = 200)
    private String website;

    /** Reference to logo (URL/path); NULL means no logo — text-only header (BR-DOC-06 fallback). */
    @Column(name = "logo_ref", length = 500)
    private String logoRef;

    /**
     * Inline company logo as a base64 data URI (e.g. {@code data:image/png;base64,...}), capped at
     * ~70 KB by ck_document_branding_logo_size (V75). NULL = no embedded logo; the renderer then
     * falls back to a text-only header. Preferred over {@link #logoRef} when present.
     */
    @Column(name = "logo_data_uri", columnDefinition = "text")
    private String logoDataUri;

    @Column(name = "footer_terms", length = 1000)
    private String footerTerms;

    @Column(name = "bank_details", length = 500)
    private String bankDetails;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected DocumentBranding() {
    }

    public DocumentBranding(Long companyId, String displayName) {
        this.companyId   = companyId;
        this.displayName = displayName;
    }
}
