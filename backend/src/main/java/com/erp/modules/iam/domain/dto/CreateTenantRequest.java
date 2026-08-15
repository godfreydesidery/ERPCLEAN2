package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Everything needed to provision a tenant (ADR-0062 P5-2).
 *
 * <p>The administrator's password is accepted rather than generated, so the platform operator can
 * hand it over out of band; it is never returned, logged or echoed. The 12-character floor mirrors
 * {@code ERP_BOOTSTRAP_ADMIN_PASSWORD}'s — a tenant created through the API must not be weaker than
 * one created by bootstrap.
 *
 * <p>Currency fields are optional and fall back to the deployment defaults, matching what bootstrap
 * does for the first tenant.
 */
public record CreateTenantRequest(
        @NotBlank @Size(max = 160) String organisationName,
        @Size(max = 64) String timeZone,
        @NotBlank @Size(max = 20) String companyCode,
        @NotBlank @Size(max = 160) String companyName,
        @NotBlank @Size(max = 20) String branchCode,
        @NotBlank @Size(max = 160) String branchName,
        @NotBlank @Size(max = 60) String adminUsername,
        @NotBlank @Size(min = 12, max = 200) String adminPassword,
        @Size(max = 160) String adminDisplayName,
        @Size(max = 3) String baseCurrency,
        @Size(max = 3) String defaultCurrency,
        List<String> enabledCurrencies) {
}
