package com.erp.modules.sales.service;

/**
 * Seeds the single per-company {@code sales_settings} row for a new company. Called from
 * {@code CompanyProvisioningService.provisionDefaults}, mirroring the {@code TaxRateSeeder} /
 * {@code DimensionSeeder} precedent.
 *
 * <p>Why this exists: the row's {@code allow_negative_stock} default is read by two different
 * layers — the Sales Settings screen and {@code NegativeStockGuard}. Provisioning the row up front
 * means neither layer has to guess what a missing row means, which is exactly how the guard and the
 * screen once ended up reporting opposite answers for the same un-configured company.
 */
public interface SalesSettingsSeeder {

    /**
     * Seeds the default Sales Settings row for the given company if it does not already exist.
     * Idempotent — a re-run for an already-provisioned company is a no-op, so the re-provision
     * endpoint heals companies created before this seeder existed.
     *
     * @param companyId the company to seed for
     */
    void seedDefaults(Long companyId);
}
