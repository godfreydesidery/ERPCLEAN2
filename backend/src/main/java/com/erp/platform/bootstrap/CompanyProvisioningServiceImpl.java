package com.erp.platform.bootstrap;

import com.erp.modules.ap.service.ApGlSeeder;
import com.erp.modules.ar.service.ArGlSeeder;
import com.erp.modules.cashbank.service.CashBankSeeder;
import com.erp.modules.cashbank.service.PettyCashFundSeeder;
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
import com.erp.modules.hr.service.LeaveTypeSeeder;
import com.erp.modules.manufacturing.service.ManufacturingGlSeeder;
import com.erp.modules.notifications.service.NotificationTypeSeeder;
import com.erp.modules.products.service.UnitOfMeasureSeeder;
import com.erp.modules.sales.service.SalesSettingsSeeder;
import com.erp.modules.sales.service.TillExpenseGlSeeder;
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
    // Sales Settings row (allow_negative_stock default = block). Provisioned up front so no layer
    // has to invent a meaning for a missing row.
    private final SalesSettingsSeeder    salesSettingsSeeder;
    // GL seeders (ADR-0013 D-15)
    private final ChartOfAccountService  chartOfAccountService;
    private final FiscalCalendarService  fiscalCalendarService;
    private final GlConfigService        glConfigService;
    // AR/AP GL seeders (ADR-0014/0015)
    private final ArGlSeeder             arGlSeeder;
    private final ApGlSeeder             apGlSeeder;
    // Cash & Bank seeder (ADR-0016 D-10)
    private final CashBankSeeder         cashBankSeeder;
    // Petty cash default fund seeder (ADR-0050 D-7 PR-B) — see the class javadoc: for a brand-new
    // company this call is a no-op (no branch yet); BootstrapRunner / BranchServiceImpl.create also
    // call PettyCashFundSeeder once a branch exists, mirroring the StockLocationSeeder precedent.
    private final PettyCashFundSeeder    pettyCashFundSeeder;
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
    private final LeaveTypeSeeder        leaveTypeSeeder;
    // Notifications type catalogue seeder (ADR-0024 D-9)
    private final NotificationTypeSeeder notificationTypeSeeder;
    // Manufacturing GL seeder (ADR-0035 D-7)
    private final ManufacturingGlSeeder  manufacturingGlSeeder;
    // Currency enablement seeder (ADR-0039 D-9)
    private final CurrencyEnablementSeeder currencyEnablementSeeder;
    // Till-expense GL account + POS_TILL_EXPENSE mapping (V97). Provisioned here so a new company
    // never posts drawer spend to the cash-SHORTAGE variance account; the POS expense path also
    // self-heals an existing company on first use, so an upgrade needs no manual step.
    private final TillExpenseGlSeeder    tillExpenseGlSeeder;
    private final CodeSequenceSeeder     codeSequenceSeeder;

    CompanyProvisioningServiceImpl(
            UnitOfMeasureSeeder    unitSeeder,
            TaxRateSeeder          taxRateSeeder,
            SalesSettingsSeeder    salesSettingsSeeder,
            ChartOfAccountService  chartOfAccountService,
            FiscalCalendarService  fiscalCalendarService,
            GlConfigService        glConfigService,
            ArGlSeeder             arGlSeeder,
            ApGlSeeder             apGlSeeder,
            CashBankSeeder         cashBankSeeder,
            PettyCashFundSeeder    pettyCashFundSeeder,
            InventoryGlSeeder      inventoryGlSeeder,
            DocumentBrandingSeeder documentBrandingSeeder,
            FixedAssetGlSeeder     fixedAssetGlSeeder,
            DimensionSeeder        dimensionSeeder,
            CrmStageSeeder         crmStageSeeder,
            HrGlSeeder             hrGlSeeder,
            HrStatutorySeeder      hrStatutorySeeder,
            LeaveTypeSeeder        leaveTypeSeeder,
            NotificationTypeSeeder notificationTypeSeeder,
            ManufacturingGlSeeder  manufacturingGlSeeder,
            CurrencyEnablementSeeder currencyEnablementSeeder,
            TillExpenseGlSeeder    tillExpenseGlSeeder,
            CodeSequenceSeeder     codeSequenceSeeder) {
        this.unitSeeder               = unitSeeder;
        this.taxRateSeeder            = taxRateSeeder;
        this.salesSettingsSeeder      = salesSettingsSeeder;
        this.chartOfAccountService    = chartOfAccountService;
        this.fiscalCalendarService    = fiscalCalendarService;
        this.glConfigService          = glConfigService;
        this.arGlSeeder               = arGlSeeder;
        this.apGlSeeder               = apGlSeeder;
        this.cashBankSeeder           = cashBankSeeder;
        this.pettyCashFundSeeder      = pettyCashFundSeeder;
        this.inventoryGlSeeder        = inventoryGlSeeder;
        this.documentBrandingSeeder   = documentBrandingSeeder;
        this.fixedAssetGlSeeder       = fixedAssetGlSeeder;
        this.dimensionSeeder          = dimensionSeeder;
        this.crmStageSeeder           = crmStageSeeder;
        this.hrGlSeeder               = hrGlSeeder;
        this.hrStatutorySeeder        = hrStatutorySeeder;
        this.leaveTypeSeeder          = leaveTypeSeeder;
        this.notificationTypeSeeder   = notificationTypeSeeder;
        this.manufacturingGlSeeder    = manufacturingGlSeeder;
        this.currencyEnablementSeeder = currencyEnablementSeeder;
        this.tillExpenseGlSeeder      = tillExpenseGlSeeder;
        this.codeSequenceSeeder       = codeSequenceSeeder;
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

        // Document-number sequences (ADR-0062 P5-6). Created up front so the PESSIMISTIC_WRITE in
        // findByCompanyIdAndEntityKindForUpdate has a row to lock. Without them the first two
        // clerks to raise the same kind of document race, both insert, and one gets a misleading
        // 409 — most likely on a new tenant's first busy morning, when all thirty kinds are unused.
        codeSequenceSeeder.seedDefaults(companyId);

        // Units of measure
        unitSeeder.seedDefaults(companyId);

        // VAT rates (ADR-0008 D-5b)
        taxRateSeeder.seedDefaults(companyId);

        // Sales Settings row, defaults = SO approval off + negative stock BLOCKED. NegativeStockGuard
        // reads this row on every sale-issue path; provisioning it here (and on re-provision) is what
        // keeps the setting the Sales Settings screen shows and the setting the till enforces identical.
        salesSettingsSeeder.seedDefaults(companyId);

        // Chart of Accounts, fiscal year + periods, GL config mappings (ADR-0013 D-15)
        chartOfAccountService.seedDefaults(companyId);
        fiscalCalendarService.seedCurrentYear(companyId);
        glConfigService.seedDefaults(companyId);

        // AR/AP GL accounts + gl_configs (ADR-0014/0015 D-13)
        arGlSeeder.seedDefaults(companyId);
        apGlSeeder.seedDefaults(companyId);

        // Default Cash & Bank account (ADR-0016 D-10)
        cashBankSeeder.seedDefaults(companyId);

        // Default petty-cash fund (ADR-0050 D-7 PR-B). No-op here for a brand-new company (no
        // branch exists yet — petty_cash_funds.branch_id is NOT NULL); BootstrapRunner /
        // BranchServiceImpl.create complete the seed once a branch exists. Effective immediately
        // when re-provisioning an existing company via CompanyServiceImpl.reprovisionDefaults.
        pettyCashFundSeeder.seedDefaults(companyId);

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

        // Leave types (P5-5). Moved here from TenantProvisioningService: sitting on the tenant path
        // alone, it covered a tenant's FIRST company and nothing else — so a second company added
        // later, and every company healed through the re-provision endpoint, opened HR -> Leave
        // empty and could not record a single day of leave. That is the same hole P5-5 was written
        // to close, left open one level down. Every other company-scoped default already lives in
        // this method for exactly that reason; this one had no business being outside it.
        leaveTypeSeeder.seedDefaults(companyId);

        // Notification type catalogue (ADR-0024 D-9)
        notificationTypeSeeder.seedDefaults(companyId);

        // Manufacturing GL accounts + gl_configs (ADR-0035 D-7)
        manufacturingGlSeeder.seedDefaults(companyId);

        // Till-expense account 5175 + gl_configs[POS_TILL_EXPENSE] (V97). Runs after the CoA seed
        // above, which is what it adopts or extends.
        tillExpenseGlSeeder.seedDefaults(companyId);

        // Company currency allow-list (ADR-0039 D-9)
        currencyEnablementSeeder.seedDefaults(companyId, baseCurrency, defaultCurrency,
                enabledCurrencies);

        log.info("Company {} provisioning complete.", companyId);
    }
}
