package com.erp.platform.bootstrap;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import com.erp.modules.manufacturing.service.ManufacturingGlSeeder;
import com.erp.modules.notifications.service.NotificationTypeSeeder;
import com.erp.modules.products.service.UnitOfMeasureSeeder;
import com.erp.modules.sales.service.SalesSettingsSeeder;
import com.erp.modules.sales.service.TillExpenseGlSeeder;
import com.erp.modules.sales.service.TaxRateSeeder;
import com.erp.modules.stock.service.InventoryGlSeeder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test for {@link CompanyProvisioningServiceImpl}.
 *
 * <p>Verifies that every seeder is called in the documented order and that the currency seeder
 * receives the correct arguments. Does not need Docker — all collaborators are Mockito mocks.
 */
@ExtendWith(MockitoExtension.class)
class CompanyProvisioningServiceImplTest {

    @Mock CodeSequenceSeeder     codeSequenceSeeder;
    @Mock UnitOfMeasureSeeder    unitSeeder;
    @Mock TaxRateSeeder          taxRateSeeder;
    @Mock SalesSettingsSeeder    salesSettingsSeeder;
    @Mock ChartOfAccountService  chartOfAccountService;
    @Mock FiscalCalendarService  fiscalCalendarService;
    @Mock GlConfigService        glConfigService;
    @Mock ArGlSeeder             arGlSeeder;
    @Mock ApGlSeeder             apGlSeeder;
    @Mock CashBankSeeder         cashBankSeeder;
    @Mock PettyCashFundSeeder    pettyCashFundSeeder;
    @Mock InventoryGlSeeder      inventoryGlSeeder;
    @Mock DocumentBrandingSeeder documentBrandingSeeder;
    @Mock FixedAssetGlSeeder     fixedAssetGlSeeder;
    @Mock DimensionSeeder        dimensionSeeder;
    @Mock CrmStageSeeder         crmStageSeeder;
    @Mock HrGlSeeder             hrGlSeeder;
    @Mock HrStatutorySeeder      hrStatutorySeeder;
    @Mock NotificationTypeSeeder notificationTypeSeeder;
    @Mock ManufacturingGlSeeder  manufacturingGlSeeder;
    @Mock CurrencyEnablementSeeder currencyEnablementSeeder;
    @Mock TillExpenseGlSeeder    tillExpenseGlSeeder;

    private CompanyProvisioningServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CompanyProvisioningServiceImpl(
                unitSeeder, taxRateSeeder, salesSettingsSeeder, chartOfAccountService, fiscalCalendarService,
                glConfigService, arGlSeeder, apGlSeeder, cashBankSeeder, pettyCashFundSeeder,
                inventoryGlSeeder, documentBrandingSeeder, fixedAssetGlSeeder, dimensionSeeder,
                crmStageSeeder, hrGlSeeder, hrStatutorySeeder, notificationTypeSeeder,
                manufacturingGlSeeder, currencyEnablementSeeder, tillExpenseGlSeeder,
                // P5-6: provisioning now seeds the document-number sequences up front.
                codeSequenceSeeder);
    }

    @Test
    void provisionDefaults_callsEverySeederExactlyOnce() {
        long companyId = 42L;
        List<String> enabled = List.of("TZS", "USD");

        service.provisionDefaults(companyId, "TZS", "TZS", enabled);

        verify(unitSeeder, times(1)).seedDefaults(companyId);
        verify(taxRateSeeder, times(1)).seedDefaults(companyId);
        verify(salesSettingsSeeder, times(1)).seedDefaults(companyId);
        verify(chartOfAccountService, times(1)).seedDefaults(companyId);
        verify(fiscalCalendarService, times(1)).seedCurrentYear(companyId);
        verify(glConfigService, times(1)).seedDefaults(companyId);
        verify(arGlSeeder, times(1)).seedDefaults(companyId);
        verify(apGlSeeder, times(1)).seedDefaults(companyId);
        verify(cashBankSeeder, times(1)).seedDefaults(companyId);
        verify(pettyCashFundSeeder, times(1)).seedDefaults(companyId);
        verify(inventoryGlSeeder, times(1)).seedDefaults(companyId);
        verify(documentBrandingSeeder, times(1)).seedDefaults(companyId);
        verify(fixedAssetGlSeeder, times(1)).seedDefaults(companyId);
        verify(dimensionSeeder, times(1)).seedBuiltIns(companyId);
        verify(crmStageSeeder, times(1)).seedDefaults(companyId);
        verify(hrGlSeeder, times(1)).seedDefaults(companyId);
        verify(hrStatutorySeeder, times(1)).seedDefaults(companyId);
        verify(notificationTypeSeeder, times(1)).seedDefaults(companyId);
        verify(manufacturingGlSeeder, times(1)).seedDefaults(companyId);
        verify(currencyEnablementSeeder, times(1))
                .seedDefaults(companyId, "TZS", "TZS", enabled);
        // V97: the till-expense account + POS_TILL_EXPENSE mapping. Without it a new company would
        // post drawer spend to the cash-SHORTAGE variance account, exactly the defect V97 closes.
        verify(tillExpenseGlSeeder, times(1)).seedDefaults(companyId);
    }

    @Test
    void provisionDefaults_glIsSeededBeforeSubledgerConfigs() {
        // ChartOfAccounts must come before AR/AP/Cash/Inventory seeders that reference GL accounts.
        service.provisionDefaults(1L, "TZS", "TZS", List.of("TZS"));

        InOrder order = inOrder(chartOfAccountService, arGlSeeder, apGlSeeder,
                cashBankSeeder, inventoryGlSeeder, tillExpenseGlSeeder);
        order.verify(chartOfAccountService).seedDefaults(1L);
        order.verify(arGlSeeder).seedDefaults(1L);
        order.verify(apGlSeeder).seedDefaults(1L);
        order.verify(cashBankSeeder).seedDefaults(1L);
        order.verify(inventoryGlSeeder).seedDefaults(1L);
        // V97: the till-expense seeder adopts or extends the chart, so it runs after the CoA seed.
        order.verify(tillExpenseGlSeeder).seedDefaults(1L);
    }

    @Test
    void provisionDefaults_currencySeederReceivesCorrectArguments() {
        List<String> enabled = List.of("TZS", "KES", "USD");

        service.provisionDefaults(7L, "KES", "KES", enabled);

        verify(currencyEnablementSeeder).seedDefaults(7L, "KES", "KES", enabled);
    }

    @Test
    void provisionDefaults_calledTwice_delegatesToSeedersWithoutGuard() {
        // The service itself has no duplicate guard — each seeder is individually idempotent.
        // This test confirms a double-call reaches each seeder twice (seeders absorb the no-op).
        service.provisionDefaults(5L, "TZS", "TZS", List.of("TZS"));
        service.provisionDefaults(5L, "TZS", "TZS", List.of("TZS"));

        verify(unitSeeder, times(2)).seedDefaults(5L);
        verify(currencyEnablementSeeder, times(2)).seedDefaults(eq(5L), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyList());
    }
}
