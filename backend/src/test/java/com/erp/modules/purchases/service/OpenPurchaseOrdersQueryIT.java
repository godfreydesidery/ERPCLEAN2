package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.purchases.domain.dto.AddPurchaseOrderLineRequest;
import com.erp.modules.purchases.domain.dto.CreatePurchaseOrderRequest;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;
import com.erp.modules.purchases.domain.dto.OpenPurchaseOrderRowDto;
import com.erp.modules.purchases.domain.dto.OpenPurchaseOrdersDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.security.BranchReadGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link OpenPurchaseOrdersQuery} against real Postgres — and in particular that "as at" really
 * reconstructs the past: receipts after the date, voids after the date and closes after the date
 * must all be read as not having happened yet.
 *
 * <p>Fixture (March 2026):
 * <pre>
 *   PO1  Alpha  branch A  ordered 01-Mar, expected 08-Mar
 *        X 10 @ 100:  6 received 05-Mar, 4 received 20-Mar
 *        Y  5 @  40:  5 received 12-Mar, that receipt VOIDED 22-Mar
 *   PO2  Beta   branch B  ordered 02-Mar, X 3 @ 50, nothing received, CLOSED 18-Mar
 *   a DRAFT order, and an order raised behind a direct receipt whose receipt was voided —
 *   neither is ever open.
 * </pre>
 * As at 10-Mar: PO1/X 4 (400), PO1/Y 5 (200), PO2/X 3 (150) = 750.
 * As at 15-Mar: PO1/X 4 (400), PO2/X 3 (150) = 550 — Y was received on the 12th.
 * As at 25-Mar: PO1/Y 5 (200) — X fully received, Y's receipt voided, PO2 closed.
 */
class OpenPurchaseOrdersQueryIT extends PurchaseReportFixture {

    @Autowired private OpenPurchaseOrdersQuery query;

    private PurchaseOrderDto po1;
    private PurchaseOrderDto po2;

    @BeforeEach
    void seed() {
        ProductDto x = product("Open X");
        ProductDto y = product("Open Y");

        po1 = placeOrder(supplier1, "TZS", new Object[] {x.uid(), "10", "100"},
                new Object[] {y.uid(), "5", "40"});
        setOrderedAt(po1, LocalDate.of(2026, 3, 1));
        jdbc.update("UPDATE purchase_orders SET expected_date = ? WHERE uid = ?",
                LocalDate.of(2026, 3, 8), po1.uid());
        GoodsReceiptDto x1 = receive(po1, 0, "6");
        setReceivedAt(x1, LocalDate.of(2026, 3, 5));
        GoodsReceiptDto x2 = receive(po1, 0, "4");
        setReceivedAt(x2, LocalDate.of(2026, 3, 20));
        GoodsReceiptDto y1 = receive(po1, 1, "5");
        voidReceipt(y1);
        setReceivedAt(y1, LocalDate.of(2026, 3, 12));
        setVoidedAt(y1, LocalDate.of(2026, 3, 22));

        asRoot(branchB);
        po2 = placeOrder(supplier2, "TZS", new Object[] {x.uid(), "3", "50"});
        setOrderedAt(po2, LocalDate.of(2026, 3, 2));
        poService.closeOrder(po2.uid());
        jdbc.update("UPDATE purchase_orders SET closed_at = ? WHERE uid = ?",
                noon(LocalDate.of(2026, 3, 18)), po2.uid());
        asRoot(branchA);

        // Never open: a draft (not placed, so not approved), and a direct-receipt order.
        poService.create(new CreatePurchaseOrderRequest(company.getUid(), supplier1.uid(), "TZS",
                null, null, List.of(new AddPurchaseOrderLineRequest(x.uid(), pcsUid,
                        new BigDecimal("9"), new BigDecimal("1"), null))));
        GoodsReceiptDto direct = receiveDirect(supplier1, y, "2", "10");
        voidReceipt(direct);
        jdbc.update("UPDATE purchase_orders SET ordered_at = ? WHERE origin = 'DIRECT_RECEIPT'",
                noon(LocalDate.of(2026, 3, 1)));
    }

    @Test
    void asAtTenth_everythingStillOwed() {
        OpenPurchaseOrdersDto r = query.report(company.getId(), LocalDate.of(2026, 3, 10), null, null);

        assertThat(r.rows()).hasSize(3);
        OpenPurchaseOrderRowDto x = r.rows().get(0);
        assertThat(x.orderNumber()).isEqualTo(po1.orderNumber());
        assertThat(x.orderedQty()).isEqualByComparingTo("10");
        assertThat(x.receivedQty()).isEqualByComparingTo("6");
        assertThat(x.outstandingQty()).isEqualByComparingTo("4");
        assertThat(x.unitCost()).isEqualByComparingTo("100");
        assertThat(x.outstandingValue()).isEqualByComparingTo("400");
        assertThat(x.orderDate()).isEqualTo("2026-03-01");
        assertThat(x.expectedDate()).isEqualTo("2026-03-08");
        assertThat(x.ageDays()).isEqualTo(9);
        assertThat(x.overdue()).isTrue();

        OpenPurchaseOrderRowDto y = r.rows().get(1);
        assertThat(y.receivedQty()).isEqualByComparingTo("0");
        assertThat(y.outstandingValue()).isEqualByComparingTo("200");

        OpenPurchaseOrderRowDto closedLater = r.rows().get(2);
        assertThat(closedLater.orderNumber()).isEqualTo(po2.orderNumber());
        assertThat(closedLater.branchName()).isEqualTo("Branch B");
        assertThat(closedLater.expectedDate()).isNull();
        assertThat(closedLater.overdue()).isFalse();

        assertThat(r.totals().outstandingValue()).isEqualByComparingTo("750");
        assertThat(r.totals().orders()).isEqualTo(2);
        assertThat(r.totals().lines()).isEqualTo(3);
        assertThat(r.asOfDate()).isEqualTo("2026-03-10");
    }

    @Test
    void asAtFifteenth_aReceiptVoidedLaterStillCountsAsReceivedThen() {
        OpenPurchaseOrdersDto r = query.report(company.getId(), LocalDate.of(2026, 3, 15), null, null);
        assertThat(r.rows()).extracting(OpenPurchaseOrderRowDto::outstandingValue)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("400"), new BigDecimal("150"));
        assertThat(r.totals().outstandingValue()).isEqualByComparingTo("550");
    }

    @Test
    void asAtTwentyFifth_theVoidReopensTheLine_andTheClosedOrderIsGone() {
        OpenPurchaseOrdersDto r = query.report(company.getId(), LocalDate.of(2026, 3, 25), null, null);
        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().get(0).outstandingQty()).isEqualByComparingTo("5");
        assertThat(r.rows().get(0).outstandingValue()).isEqualByComparingTo("200");
        assertThat(r.rows().get(0).ageDays()).isEqualTo(24);
        assertThat(r.totals().outstandingValue()).isEqualByComparingTo("200");
        assertThat(r.totals().orders()).isEqualTo(1);
    }

    @Test
    void beforeAnyOrderWasPlaced_nothingIsOpen() {
        OpenPurchaseOrdersDto r = query.report(company.getId(), LocalDate.of(2026, 2, 28), null, null);
        assertThat(r.rows()).isEmpty();
        assertThat(r.totals().outstandingValue()).isEqualByComparingTo("0");
    }

    @Test
    void filters_supplierAndBranch() {
        LocalDate tenth = LocalDate.of(2026, 3, 10);
        OpenPurchaseOrdersDto beta = query.report(company.getId(), tenth, null, supplier2.uid());
        assertThat(beta.rows()).hasSize(1);
        assertThat(beta.totals().outstandingValue()).isEqualByComparingTo("150");
        assertThat(beta.supplierName()).isEqualTo("Beta Wholesale");

        OpenPurchaseOrdersDto a = query.report(company.getId(), tenth, branchA.getUid(), null);
        assertThat(a.totals().outstandingValue()).isEqualByComparingTo("600");
    }

    @Test
    void noDate_meansToday() {
        OpenPurchaseOrdersDto r = query.report(company.getId(), null, null, null);
        assertThat(r.asOfDate()).isEqualTo(LocalDate.now(ZONE).toString());
    }

    @Test
    void nonRootClerk_isRefusedABranchTheyAreNotAssignedTo() {
        asClerk();
        LocalDate tenth = LocalDate.of(2026, 3, 10);
        assertThatCode(() -> query.report(company.getId(), tenth, branchA.getUid(), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> query.report(company.getId(), tenth, null, null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> query.report(company.getId(), tenth, branchB.getUid(), null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(BranchReadGuard.branchNotAssigned().getMessage());
    }
}
