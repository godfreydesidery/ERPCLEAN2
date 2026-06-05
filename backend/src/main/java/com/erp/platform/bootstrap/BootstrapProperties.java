package com.erp.platform.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fresh-DB bootstrap config (bound from {@code erp.bootstrap.*}). When enabled and the DB has no
 * organisation, the app creates org + company + default branch + root admin from these values.
 */
@ConfigurationProperties(prefix = "erp.bootstrap")
public record BootstrapProperties(
        boolean enabled,
        String organisationName,
        String companyCode,
        String companyName,
        String branchCode,
        String branchName,
        String adminUsername,
        String adminDisplayName,
        String adminPassword,
        String timeZone) {
}
