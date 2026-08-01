package com.erp.platform.bootstrap;

import java.util.List;

/**
 * Idempotent per-company defaults provisioner. Runs the full seeder chain (units of measure, tax
 * rates, sales settings, chart of accounts, fiscal calendar, GL configs,
 * AR/AP/Cash/Inventory/FA/HR/Manufacturing GL seeders, costing dimensions, CRM stages,
 * notifications, currency enablement) for a single company.
 *
 * <p>Every seeder is individually idempotent — a re-run for an already-provisioned company is a
 * no-op. This makes the endpoint safe to call on existing companies to heal any partial-provisioning
 * gap.
 *
 * <p>Note: {@link com.erp.modules.stock.service.StockLocationSeeder} is BRANCH-scoped and is NOT
 * managed here; it is called by {@link com.erp.modules.iam.service.BranchServiceImpl} when a branch
 * is created, and by {@link BootstrapRunner} for the bootstrap branch.
 */
public interface CompanyProvisioningService {

    /**
     * Provision all company-scoped defaults for the given company.
     *
     * @param companyId        the internal (numeric) company id — must be persisted before calling
     * @param baseCurrency     the ledger base currency code, e.g. {@code "TZS"}
     * @param defaultCurrency  the default document currency (often same as base)
     * @param enabledCurrencies the set of currency codes to enable for this company
     */
    void provisionDefaults(long companyId, String baseCurrency, String defaultCurrency,
                           List<String> enabledCurrencies);
}
