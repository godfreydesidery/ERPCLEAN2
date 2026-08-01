package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.LeafCostResolver;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.entity.SalesInvoiceLine;
import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.domain.enums.BelowCostAction;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.PermissionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link BelowCostGuard} — the synchronous "sale at or below cost" policy
 * ({@code sales_settings.below_cost_action}, V93).
 *
 * <p>See {@link BelowCostSettingCrossLayerContractTest} for the companion test that pins this
 * guard's answer to the one the Sales Settings API reports for the same company state.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BelowCostGuardTest {

    @Mock SalesSettingsRepository settings;
    @Mock LeafCostResolver costs;
    @Mock PermissionResolver permissionResolver;
    @Mock AuditService audit;

    @InjectMocks BelowCostGuard guard;

    private static final Long   COMPANY_ID  = 1L;
    private static final Long   BRANCH_ID   = 10L;
    private static final Long   INVOICE_ID  = 900L;
    private static final String INVOICE_UID = "INVUID000000000000000000001";
    private static final Long   PRODUCT_ID  = 100L;
    private static final String OVERRIDE    = "SALES.BELOW_COST.OVERRIDE";

    // -------------------------------------------------------------------------
    // The comparison itself — "equal or LESS than cost" triggers.
    // -------------------------------------------------------------------------

    @Test
    void blocksWhenNetUnitPriceIsBelowCost() {
        givenAction(BelowCostAction.BLOCK);
        givenCost("1000");

        assertThatThrownBy(() -> check(line("Widget", "900", "1"), false))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Widget")
                .hasMessageContaining("at or below cost");
    }

    @Test
    void blocksWhenNetUnitPriceIsExactlyCost_becauseTheRuleIsEqualOrLess() {
        // The owner's rule is "equal or less than cost" — selling at exactly cost is a zero-margin
        // sale and must trigger. A `<` comparison would let this through, which is the easiest way
        // to get this feature subtly wrong.
        givenAction(BelowCostAction.BLOCK);
        givenCost("1000");

        assertThatThrownBy(() -> check(line("Widget", "1000", "1"), false))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Widget");
    }

    @Test
    void allowsWhenNetUnitPriceIsAboveCost() {
        givenAction(BelowCostAction.BLOCK);
        givenCost("1000");

        assertThatCode(() -> check(line("Widget", "1000.0001", "1"), false))
                .doesNotThrowAnyException();
    }

    @Test
    void comparesPerBaseUnit_notPerLine() {
        // A 10-unit line at 12 000 net is 1 200/unit — ABOVE the 1 000 cost. Comparing the whole
        // line amount against the unit cost would wrongly read it as fine only by accident; comparing
        // it the other way round (line net vs cost x qty) is the same bug mirrored. Pin the unit maths.
        givenAction(BelowCostAction.BLOCK);
        givenCost("1000");

        assertThatCode(() -> check(line("Widget", "12000", "10"), false))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> check(line("Widget", "9000", "10"), false))
                .isInstanceOf(ConflictException.class);   // 900/unit — under cost
    }

    // -------------------------------------------------------------------------
    // Actions.
    // -------------------------------------------------------------------------

    @Test
    void offReturnsImmediately_neverEvenResolvesCosts() {
        givenAction(BelowCostAction.OFF);

        check(line("Widget", "1", "1"), false);

        verify(costs, never()).avgCosts(anyLong(), anyLong(), any());
    }

    @Test
    void noSettingsRowBehavesAsOff() {
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(Optional.empty());

        check(line("Widget", "1", "1"), false);

        verify(costs, never()).avgCosts(anyLong(), anyLong(), any());
    }

    @Test
    void warnAllowsTheSale_andRecordsIt() {
        givenAction(BelowCostAction.WARN);
        givenCost("1000");

        assertThatCode(() -> check(line("Widget", "900", "1"), false))
                .doesNotThrowAnyException();

        verify(audit).record(any());
    }

    @Test
    void approveWithoutPermission_rejectsEvenWhenTheFlagIsSet() {
        // The flag is client-supplied; on its own it must authorise nothing, or any till could set it.
        givenAction(BelowCostAction.APPROVE);
        givenCost("1000");
        when(permissionResolver.hasPermission(any(), eq(OVERRIDE), anyLong())).thenReturn(false);

        assertThatThrownBy(() -> check(line("Widget", "900", "1"), true))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Widget")
                .hasMessageContaining("supervisor needs to approve");
    }

    @Test
    void approveWithPermissionButNoFlag_rejects() {
        // Holding the permission is not the same as having asked for the override — a manager
        // ringing an ordinary sale should still be stopped and told, not silently waved through.
        givenAction(BelowCostAction.APPROVE);
        givenCost("1000");
        when(permissionResolver.hasPermission(any(), eq(OVERRIDE), anyLong())).thenReturn(true);

        assertThatThrownBy(() -> check(line("Widget", "900", "1"), false))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("supervisor needs to approve");
    }

    @Test
    void approveWithFlagAndPermission_allowsAndAudits() {
        givenAction(BelowCostAction.APPROVE);
        givenCost("1000");
        when(permissionResolver.hasPermission(any(), eq(OVERRIDE), anyLong())).thenReturn(true);

        assertThatCode(() -> check(line("Widget", "900", "1"), true))
                .doesNotThrowAnyException();

        verify(audit).record(any());
    }

    @Test
    void blockRejectsEvenWithFlagAndPermission() {
        givenAction(BelowCostAction.BLOCK);
        givenCost("1000");
        when(permissionResolver.hasPermission(any(), eq(OVERRIDE), anyLong())).thenReturn(true);

        assertThatThrownBy(() -> check(line("Widget", "900", "1"), true))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("priced at or below cost")
                .hasMessageContaining("this sale cannot be completed");
    }

    // -------------------------------------------------------------------------
    // Unknown cost never blocks (owner decision) — but is recorded.
    // -------------------------------------------------------------------------

    @Test
    void unknownCostAllowsTheSaleUnderBlock_andRecordsThatItWasUnchecked() {
        givenAction(BelowCostAction.BLOCK);
        when(costs.avgCosts(eq(COMPANY_ID), eq(BRANCH_ID), any())).thenReturn(Map.of());   // no cost

        assertThatCode(() -> check(line("Widget", "1", "1"), false))
                .doesNotThrowAnyException();

        verify(audit).record(any());
    }

    @Test
    void zeroCostIsTreatedAsUnknown_neverBlocks() {
        // avg_cost = 0 is "no cost established yet", not "this item is free" — a `<=` against zero
        // would otherwise reject every single line for a company that has never costed its stock.
        givenAction(BelowCostAction.BLOCK);
        givenCost("0");

        assertThatCode(() -> check(line("Widget", "5000", "1"), false))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Batching — one cost lookup for the whole invoice, never one per line.
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void resolvesEveryLinesCostInASingleBatchCall() {
        givenAction(BelowCostAction.WARN);
        when(costs.avgCosts(eq(COMPANY_ID), eq(BRANCH_ID), any()))
                .thenReturn(Map.of(101L, new BigDecimal("10"),
                                   102L, new BigDecimal("20"),
                                   103L, new BigDecimal("30")));

        guard.assertNotBelowCost(COMPANY_ID, BRANCH_ID, INVOICE_ID, INVOICE_UID, List.of(
                new BelowCostGuard.PricedLine(101L, "A", new BigDecimal("100"), BigDecimal.ONE),
                new BelowCostGuard.PricedLine(102L, "B", new BigDecimal("200"), BigDecimal.ONE),
                new BelowCostGuard.PricedLine(103L, "C", new BigDecimal("300"), BigDecimal.ONE),
                // Same product twice — must not produce a second lookup key either.
                new BelowCostGuard.PricedLine(101L, "A", new BigDecimal("100"), BigDecimal.ONE)),
                false);

        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(costs, times(1)).avgCosts(eq(COMPANY_ID), eq(BRANCH_ID), ids.capture());
        assertThat(ids.getValue())
                .as("one batch call carrying every distinct product on the invoice")
                .containsExactly(101L, 102L, 103L);
    }

    // -------------------------------------------------------------------------
    // VAT-INCLUSIVE (ADR-0056) — the subtlest failure mode.
    // -------------------------------------------------------------------------

    @Test
    void vatInclusiveLine_comparesTheNetPrice_notTheGrossOneTheCustomerPays() {
        // A VAT-inclusive price list stores the GROSS in unit_price_amount. Here the shelf price is
        // 1 100 (gross) against a cost of 1 000 — comparing gross against cost says "above cost, fine".
        // But 1 100 gross at 18% is only 932 NET, so the company loses ~68 per unit on every sale.
        // The guard must see 932, and therefore trigger. Driving the REAL InvoiceTotalsCalculator here
        // (rather than hand-computing a net) is deliberate: it proves the guard consumes exactly the
        // figure the invoice will actually book, with no second VAT implementation to drift from it.
        InvoiceTotalsCalculator calculator = new InvoiceTotalsCalculator(new ObjectMapper());
        SalesInvoice invoice = new SalesInvoice(COMPANY_ID, BRANCH_ID, 200L, 300L, "TZS", 1L);
        SalesInvoiceLine inclusiveLine = new SalesInvoiceLine(invoice, (short) 1,
                PRODUCT_ID, "PROD-0001", "Widget", 200L, "PCS",
                BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("1100.0000"), new BigDecimal("1100.0000"),
                VatStatus.STANDARD, new BigDecimal("0.1800"), 1L);
        inclusiveLine.setPriceInclusive(true);
        calculator.recompute(invoice, List.of(inclusiveLine));

        // Sanity: gross is above cost, net is below it — the exact trap this test exists for.
        assertThat(inclusiveLine.getGrossAmount())
                .isEqualByComparingTo("1100");
        assertThat(inclusiveLine.getNetAmount())
                .isEqualByComparingTo("932");

        givenAction(BelowCostAction.BLOCK);
        givenCost("1000");

        assertThatThrownBy(() -> guard.assertNotBelowCost(
                COMPANY_ID, BRANCH_ID, INVOICE_ID, INVOICE_UID,
                List.of(new BelowCostGuard.PricedLine(PRODUCT_ID, inclusiveLine.getProductName(),
                        inclusiveLine.getNetAmount(), inclusiveLine.getQtyInBase())),
                false))
                .as("a gross price above cost whose NET is below cost must still trigger")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Widget");
    }

    // -------------------------------------------------------------------------
    // Error-message hygiene.
    // -------------------------------------------------------------------------

    @Test
    void rejectionNamesTheProductButLeaksNoIdsUidsOrCostFigures() {
        givenAction(BelowCostAction.BLOCK);
        givenCost("1000");

        assertThatThrownBy(() -> check(line("Widget", "900", "1"), false))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Widget")
                .hasMessageNotContaining(INVOICE_UID)
                .hasMessageNotContaining(String.valueOf(PRODUCT_ID))
                .hasMessageNotContaining("1000")      // the cost is never quoted back to a cashier
                .hasMessageNotContaining("900")
                .hasMessageNotContaining("below_cost_action")
                .hasMessageNotContaining("V93");
    }

    // -------------------------------------------------------------------------

    private void check(BelowCostGuard.PricedLine line, boolean approvalSupplied) {
        guard.assertNotBelowCost(COMPANY_ID, BRANCH_ID, INVOICE_ID, INVOICE_UID,
                List.of(line), approvalSupplied);
    }

    private static BelowCostGuard.PricedLine line(String name, String netAmount, String qtyInBase) {
        return new BelowCostGuard.PricedLine(PRODUCT_ID, name,
                new BigDecimal(netAmount), new BigDecimal(qtyInBase));
    }

    private void givenAction(BelowCostAction action) {
        SalesSettings row = new SalesSettings(COMPANY_ID, null);
        row.setBelowCostAction(action);
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(row));
    }

    private void givenCost(String avgCost) {
        when(costs.avgCosts(eq(COMPANY_ID), eq(BRANCH_ID), any()))
                .thenReturn(Map.of(PRODUCT_ID, new BigDecimal(avgCost)));
    }
}
