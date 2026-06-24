package com.erp.modules.documents.domain.dto;

import jakarta.validation.constraints.Size;

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
        /**
         * Inline company logo as a base64 data URI ({@code data:image/png;base64,...}), or blank to
         * clear it. Capped at ~70 KB; the service also rejects anything that is not a PNG/JPEG data URI.
         */
        @Size(max = 72000, message = "Logo is too large — use a smaller image.")
        String logoDataUri,
        String footerTerms,
        String bankDetails,
        Long   version
) {}
