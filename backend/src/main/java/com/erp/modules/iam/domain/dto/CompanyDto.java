package com.erp.modules.iam.domain.dto;

import com.erp.modules.iam.domain.entity.Company;

/**
 * Response shape for a company (DATA-MODEL §1.2). Carries both {@code id} (numeric, serialised as a
 * JSON string globally) and {@code uid} (the URL identifier).
 */
public record CompanyDto(
        Long id,
        String uid,
        Long organisationId,
        String code,
        String name,
        String legalName,
        String taxId,
        String vrn,
        String logoRef,
        Short fiscalYearStartMonth,
        String timeZone,
        String baseCurrency,
        String status,
        String contactPhone,
        String contactEmail,
        String addressLine1,
        String addressLine2,
        String city,
        String region,
        String country) {

    public static CompanyDto from(Company c) {
        return new CompanyDto(
                c.getId(),
                c.getUid(),
                c.getOrganisation().getId(),
                c.getCode(),
                c.getName(),
                c.getLegalName(),
                c.getTaxId(),
                c.getVrn(),
                c.getLogoRef(),
                c.getFiscalYearStartMonth(),
                c.getTimeZone(),
                c.getBaseCurrency(),
                c.getStatus().name(),
                c.getContactPhone(),
                c.getContactEmail(),
                c.getAddressLine1(),
                c.getAddressLine2(),
                c.getCity(),
                c.getRegion(),
                c.getCountry());
    }
}
