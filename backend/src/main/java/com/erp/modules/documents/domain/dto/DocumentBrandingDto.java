package com.erp.modules.documents.domain.dto;

import com.erp.modules.documents.domain.entity.DocumentBranding;
import java.time.Instant;

/** Response DTO for a document branding profile (ADR-0023 D-2). */
public record DocumentBrandingDto(
        Long    id,
        String  uid,
        Long    companyId,
        String  displayName,
        String  legalName,
        String  taxId,
        String  addressLine1,
        String  addressLine2,
        String  city,
        String  region,
        String  country,
        String  postalCode,
        String  contactPhone,
        String  contactEmail,
        String  website,
        String  logoRef,
        String  footerTerms,
        String  bankDetails,
        Instant updatedAt
) {
    public static DocumentBrandingDto from(DocumentBranding b) {
        return new DocumentBrandingDto(
                b.getId(), b.getUid(), b.getCompanyId(),
                b.getDisplayName(), b.getLegalName(), b.getTaxId(),
                b.getAddressLine1(), b.getAddressLine2(),
                b.getCity(), b.getRegion(), b.getCountry(), b.getPostalCode(),
                b.getContactPhone(), b.getContactEmail(), b.getWebsite(),
                b.getLogoRef(), b.getFooterTerms(), b.getBankDetails(),
                b.getUpdatedAt());
    }
}
