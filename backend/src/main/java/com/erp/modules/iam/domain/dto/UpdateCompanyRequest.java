package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Update mutable company fields. Code/organisation are not changed here (identity-ish). */
public record UpdateCompanyRequest(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 200) String legalName,
        @Size(max = 60) String taxId,
        @Size(max = 64) String timeZone,
        /**
         * Optional new base currency (ADR-0039 D-9 / OQ-CCY-08). Requires
         * {@code COMPANY.CURRENCY.CHANGE} permission and is blocked when any
         * {@code journal_entries} row exists for the company (post-GL-transaction guard).
         * Null / blank = leave unchanged.
         */
        @Size(min = 3, max = 3) String baseCurrency,
        /** P2 D7 contact/address block — all optional, nullable = leave unchanged. */
        @Size(max = 40) String vrn,
        @Size(max = 40) String contactPhone,
        @Size(max = 160) String contactEmail,
        @Size(max = 160) String addressLine1,
        @Size(max = 160) String addressLine2,
        @Size(max = 80) String city,
        @Size(max = 80) String region,
        @Size(max = 80) String country) {
}
