package com.erp.platform.bootstrap;

import com.erp.modules.ap.service.ApGlSeeder;
import com.erp.modules.ar.service.ArGlSeeder;
import com.erp.modules.cashbank.service.CashBankSeeder;
import com.erp.modules.costing.service.DimensionSeeder;
import com.erp.modules.crm.service.CrmStageSeeder;
import com.erp.modules.documents.service.DocumentBrandingSeeder;
import com.erp.modules.fixedassets.service.FixedAssetGlSeeder;
import com.erp.modules.fx.service.CurrencyEnablementSeeder;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.hr.service.HrGlSeeder;
import com.erp.modules.hr.service.HrStatutorySeeder;
import com.erp.modules.manufacturing.service.ManufacturingGlSeeder;
import com.erp.modules.notifications.service.NotificationTypeSeeder;
import com.erp.modules.products.service.UnitOfMeasureSeeder;
import com.erp.modules.sales.service.TaxRateSeeder;
import com.erp.modules.stock.service.InventoryGlSeeder;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the ordered, idempotent per-company seeder chain.
 *
 * <p>Order matters: GL accounts are seeded before the sub-ledger configs that reference them.
 * See the ADR comments inline for each phase.
 *
 * <p>{@link com.erp.modules.stock.service.StockLocationSeeder} is intentionally EXCLUDED — it is
 * branch-scoped, not company-scoped, and is owned by BranchServiceImpl / BootstrapRunner.
 */
@Service
class CompanyProvisioningServiceImpl implements CompanyProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(CompanyProvisioningServiceImpl.class);

    private final UnitOfMeasureSeeder    unitSeeder;
    private final TaxRateSeeder          taxRateSeeder;
    // GL seeders (ADR-0013 D-15)
    private final ChartOfAccountService  chartOfAccountService;
    private final FiscalCalendarService  fiscalCalendarService;
    private final GlConfigService        glConfigService;
    // AR/AP GL seeders (ADR-0014/0015)
    private final ArGlSeeder             arGlSeeder;
    private final ApGlSeeder             apGlSeeder;
    // Cash & Bank seeder (ADR-0016 D-10)
    private final CashBankSeeder         cashBankSeeder;
    // Inventory Valuation & COGS seeder (ADR-0020 D-8)
    private final InventoryGlSeeder      inventoryGlSeeder;
    // Document branding + template registry seeder (ADR-0023 D-10)
    private final DocumentBrandingSeeder documentBrandingSeeder;
    // Fixed Assets GL seeder (ADR-0030 D-7)
    private final FixedAssetGlSeeder     fixedAssetGlSeeder;
    // Costing built-in dimensions seeder (ADR-0025 D-9)
    private final DimensionSeeder        dimensionSeeder;
    // CRM pipeline stage defaults seeder (ADR-0031 D-5)
    private final CrmStageSeeder         crmStageSeeder;
    // HR & Payroll GL + statutory seeders (ADR-0032 D-8/D-9)
    private final HrGlSeeder             hrGlSeeder;
    private final HrStatutorySeeder      hrStatutorySeeder;
    // Notifications type catalogue seeder (ADR-0024 D-9)
    private final NotificationTypeSeeder notificationTypeSeeder;
    // Manufacturing GL seeder (ADR-0035 D-7)
    private final ManufacturingGlSeeder  manufacturingGlSeeder;
    // Currency enablement seeder (ADR-0039 D-9)
    private final CurrencyEnablementSeeder currencyEnablementSeeder;

    CompanyProvisioningServiceImpl(
            UnitOfMeasureSeeder    unitSeeder,
            TaxRateSeeder          taxRateSeeder,
            ChartOfAccountService  chartOfAccountService,
            FiscalCalendarService  fiscalCalendarService,
            GlConfigService        glConfigService,
            ArGlSeeder             arGlSeeder,
            ApGlSeeder             apGlSeeder,
            CashBankSeeder         cashBankSeeder,
            InventoryGlSeeder      inventoryGlSeeder,
            DocumentBrandingSeeder documentBrandingSeeder,
            FixedAssetGlSeeder     fixedAssetGlSeeder,
            DimensionSeeder        dimensionSeeder,
            CrmStageSeeder         crmStageSeeder,
            HrGlSeeder             hrGlSeeder,
            HrStatutorySeeder      hrStatutorySeeder,
            NotificationTypeSeeder notificationTypeSeeder,
            ManufacturingGlSeeder  manufacturingGlSeeder,
            CurrencyEnablementSeeder currencyEnablementSeeder) {
        this.unitSeeder               = unitSeeder;
        this.taxRateSeeder            = taxRateSeeder;
        this.chartOfAccountService    = chartOfAccountService;
        this.fiscalCalendarService    = fiscalCalendarService;
        this.glConfigService          = glConfigService;
        this.arGlSeeder               = arGlSeeder;
        this.apGlSeeder               = apGlSeeder;
        this.cashBankSeeder           = cashBankSeeder;
        this.inventoryGlSeeder        = inventoryGlSeeder;
        this.documentBrandingSeeder   = documentBrandingSeeder;
        this.fixedAssetGlSeeder       = fixedAssetGlSeeder;
        this.dimensionSeeder          = dimensionSeeder;
        this.crmStageSeeder           = crmStageSeeder;
        this.hrGlSeeder               = hrGlSeeder;
        this.hrStatutorySeeder        = hrStatutorySeeder;
        this.notificationTypeSeeder   = notificationTypeSeeder;
        this.manufacturingGlSeeder    = manufacturingGlSeeder;
        this.currencyEnablementSeeder = currencyEnablementSeeder;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Propagation REQUIRED: joins the caller's transaction when called from
     * {@link com.erp.modules.iam.service.CompanyServiceImpl#create} or
     * {@link com.erp.modules.iam.service.CompanyServiceImpl#reprovisionDefaults}, and runs in its
     * own transaction when called standalone (e.g. from {@link BootstrapRunner}).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void provisionDefaults(long companyId, String baseCurrency, String defaultCurrency,
                                  List<String> enabledCurrencies) {
        log.info("Provisioning defaults for company {} (base={}, default={}, enabled={}).",
                companyId, baseCurrency, defaultCurrency, enabledCurrencies);

        // Units of measure
        unitSeeder.seedDefaults(companyId);

        // VAT rates (ADR-0008 D-5b)
        taxRateSeeder.seedDefaults(companyId);

        // Chart of Accounts, fiscal year + periods, GL config mappings (ADR-0013 D-15)
        chartOfAccountService.seedDefaults(companyId);
        fiscalCalendarService.seedCurrentYear(companyId);
        glConfigService.seedDefaults(companyId);

        // AR/AP GL accounts + gl_configs (ADR-0014/0015 D-13)
        arGlSeeder.seedDefaults(companyId);
        apGlSeeder.seedDefaults(companyId);

        // Default Cash & Bank account (ADR-0016 D-10)
        cashBankSeeder.seedDefaults(companyId);

        // GRNI + Stock Adjustment GL accounts + gl_configs (ADR-0020 D-8)
        inventoryGlSeeder.seedDefaults(companyId);

        // Document branding profile + template registry (ADR-0023 D-10)
        documentBrandingSeeder.seedDefaults(companyId);

        // Fixed Assets GL accounts + gl_configs (ADR-0030 D-7)
        fixedAssetGlSeeder.seedDefaults(companyId);

        // Built-in costing dimensions COST_CENTRE + DEPARTMENT (ADR-0025 D-9)
        dimensionSeeder.seedBuiltIns(companyId);

        // Default CRM pipeline stages (ADR-0031 D-5)
        crmStageSeeder.seedDefaults(companyId);

        // HR GL accounts + gl_configs + TZ statutory defaults (ADR-0032 D-8/D-9)
        hrGlSeeder.seedDefaults(companyId);
        hrStatutorySeeder.seedDefaults(companyId);

        // Notification type catalogue (ADR-0024 D-9)
        notificationTypeSeeder.seedDefaults(companyId);

        // Manufacturing GL accounts + gl_configs (ADR-0035 D-7)
        manufacturingGlSeeder.seedDefaults(companyId);

        // Company currency allow-list (ADR-0039 D-9)
        currencyEnablementSeeder.seedDefaults(companyId, baseCurrency, defaultCurrency,
                enabledCurrencies);

        log.info("Company {} provisioning complete.", companyId);
    }
}
