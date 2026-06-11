package com.erp.modules.documents.domain.dto;

/** Request DTO to update a company branding profile (ADR-0023 D-11). */
public record UpdateDocumentBrandingRequest(
        String displayName,
        String legalName,
        String taxId,
        String addressLine1,
        String addressLine2,
        String city,
        String region,
        String country,
        String postalCode,
        String contactPhone,
        String contactEmail,
        String website,
        String logoRef,
        String footerTerms,
        String bankDetails,
        Long   version
) {}
