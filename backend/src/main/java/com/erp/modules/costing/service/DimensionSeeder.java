package com.erp.modules.costing.service;

/**
 * Seeds the two built-in dimension rows (COST_CENTRE + DEPARTMENT) for a new company
 * (ADR-0025 D-9). Called from {@code BootstrapRunner} + {@code CompanyService.create}, mirroring
 * the {@code TaxRateSeeder} / {@code InventoryGlSeeder} precedent.
 */
public interface DimensionSeeder {

    /**
     * Seeds COST_CENTRE and DEPARTMENT dimensions for the given company if they do not already
     * exist. Idempotent — may be called multiple times safely (ON CONFLICT DO NOTHING semantics
     * mirrored in Java: checks before insert).
     *
     * @param companyId the company to seed for
     */
    void seedBuiltIns(Long companyId);
}
