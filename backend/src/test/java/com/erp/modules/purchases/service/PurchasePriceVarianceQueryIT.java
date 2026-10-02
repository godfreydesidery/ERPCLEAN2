package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.dto.PurchasePriceVarianceDto;
import com.erp.modules.purchases.domain.dto.PurchasePriceVarianceRowDto;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.security.BranchReadGuard;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link PurchasePriceVarianceQuery} against real Postgres.
 *
 * <p>Fixture (March 2026, branch A unless said otherwise):
 * <pre>
 *   GR1  Alpha  X 10, PO 100  billed 6 @ 104 + 4 @ 99  -> weighted 102, +2/unit, +20 total, +2.00%
 *   GR2  Beta   Y  5, PO  40  billed 5 @ 40 (no variance) + a DRAFT bill at 45 (ignored)
 *   GR3  Alpha  Y  4, PO  50  receipt cost forced to 52  -> +2/unit, +8 total, +4.00%; not billed
 *   GR4  Alpha  X  2, PO 100  billed 2 @ 120 but the receipt is VOIDED — excluded entirely
 *   GR5  Alpha  X  1, PO 100  branch B, billed 1 @ 90  -> -10
 * </pre>
 */
class PurchasePriceVarianceQueryIT extends PurchaseReportFixture {

    private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate TO   = LocalDate.of(2026, 3, 31);

    @Autowired private PurchasePriceVarianceQuery query;

    private GoodsReceiptDto gr1;
    private GoodsReceiptDto gr3;

    @BeforeEach
    void seed() {
        ProductDto x = product("PPV X");
        ProductDto y = product("PPV Y");
        LocalDate billDay = LocalDate.of(2026, 3, 28);

        PurchaseOrderDto po1 = placeOrder(supplier1, "TZS", new Object[] {x.uid(), "10", "100"});
        gr1 = receive(po1, 0, "10");
        setReceivedAt(gr1, LocalDate.of(2026, 3, 5));
        bill(supplier1, branchA, gr1, "6", "104", billDay, "0", "APPROVED");
        bill(supplier1, branchA, gr1, "4", "99", billDay, "0", "PAID");

        PurchaseOrderDto po2 = placeOrder(supplier2, "TZS", new Object[] {y.uid(), "5", "40"});
        GoodsReceiptDto gr2 = receive(po2, 0, "5");
        setReceivedAt(gr2, LocalDate.of(2026, 3, 6));
        bill(supplier2, branchA, gr2, "5", "40", billDay, "0", "MATCHED");
        bill(supplier2, branchA, gr2, "5", "45", billDay, "0", "DRAFT");

        PurchaseOrderDto po3 = placeOrder(supplier1, "TZS", new Object[] {y.uid(), "4", "50"});
        gr3 = receive(po3, 0, "4");
        setReceivedAt(gr3, LocalDate.of(2026, 3, 7));
        jdbc.update("UPDATE goods_receipt_lines SET unit_cost_amount = 52, line_cost_amount = 208 "
                + "WHERE uid = ?", gr3.lines().get(0).uid());

        PurchaseOrderDto po4 = placeOrder(supplier1, "TZS", new Object[] {x.uid(), "2", "100"});
        GoodsReceiptDto gr4 = receive(po4, 0, "2");
        voidReceipt(gr4);
        setReceivedAt(gr4, LocalDate.of(2026, 3, 8));
        bill(supplier1, branchA, gr4, "2", "120", billDay, "0", "APPROVED");

        asRoot(branchB);
        PurchaseOrderDto po5 = placeOrder(supplier1, "TZS", new Object[] {x.uid(), "1", "100"});
        GoodsReceiptDto gr5 = receive(po5, 0, "1");
        setReceivedAt(gr5, LocalDate.of(2026, 3, 9));
        bill(supplier1, branchB, gr5, "1", "90", billDay, "0", "APPROVED");
        asRoot(branchA);
    }

    @Test
    void withBills_listsBillAndReceiptVariance_weightedAcrossBills() {
        PurchasePriceVarianceDto r = query.report(company.getId(), FROM, TO, null, null, true);

        assertThat(r.billsShown()).isTrue();
        assertThat(r.rows()).extracting(PurchasePriceVarianceRowDto::receiptNumber)
                .containsExactly(gr1.receiptNumber(), gr3.receiptNumber(),
                        r.rows().get(2).receiptNumber());
        assertThat(r.rows()).hasSize(3);

        PurchasePriceVarianceRowDto billed = r.rows().get(0);
        assertThat(billed.poPrice()).isEqualByComparingTo("100");
        assertThat(billed.receiptCost()).isEqualByComparingTo("100");
        assertThat(billed.receiptVarianceTotal()).isEqualByComparingTo("0");
        assertThat(billed.billedQty()).isEqualByComparingTo("10");
        assertThat(billed.billPrice()).isEqualByComparingTo("102");
        assertThat(billed.billVariancePerUnit()).isEqualByComparingTo("2");
        assertThat(billed.billVarianceTotal()).isEqualByComparingTo("20");
        assertThat(billed.billVariancePct()).isEqualByComparingTo("2.00");
        assertThat(billed.supplierName()).isEqualTo("Alpha Traders");

        PurchasePriceVarianceRowDto receiptOnly = r.rows().get(1);
        assertThat(receiptOnly.receiptVariancePerUnit()).isEqualByComparingTo("2");
        assertThat(receiptOnly.receiptVarianceTotal()).isEqualByComparingTo("8");
        assertThat(receiptOnly.receiptVariancePct()).isEqualByComparingTo("4.00");
        assertThat(receiptOnly.billPrice()).isNull();
        assertThat(receiptOnly.billVarianceTotal()).isNull();

        PurchasePriceVarianceRowDto under = r.rows().get(2);
        assertThat(under.billVarianceTotal()).isEqualByComparingTo("-10");
        assertThat(under.billVariancePct()).isEqualByComparingTo("-10.00");

        assertThat(r.totals().lines()).isEqualTo(3);
        assertThat(r.totals().receiptVarianceTotal()).isEqualByComparingTo("8");
        assertThat(r.totals().billVarianceTotal()).isEqualByComparingTo("10");   // 20 - 10
        assertThat(r.totals().rowsInOtherCurrency()).isZero();
    }

    @Test
    void withoutBillAccess_onlyReceiptVarianceIsListed_andBillColumnsAreNull() {
        PurchasePriceVarianceDto r = query.report(company.getId(), FROM, TO, null, null, false);

        assertThat(r.billsShown()).isFalse();
        assertThat(r.rows()).hasSize(1);
        PurchasePriceVarianceRowDto row = r.rows().get(0);
        assertThat(row.receiptNumber()).isEqualTo(gr3.receiptNumber());
        assertThat(row.billedQty()).isNull();
        assertThat(row.billPrice()).isNull();
        assertThat(row.billVariancePerUnit()).isNull();
        assertThat(row.billVarianceTotal()).isNull();
        assertThat(r.totals().billVarianceTotal()).isNull();
        assertThat(r.totals().receiptVarianceTotal()).isEqualByComparingTo("8");
    }

    @Test
    void filters_branchAndSupplier() {
        PurchasePriceVarianceDto a = query.report(company.getId(), FROM, TO, branchA.getUid(), null, true);
        assertThat(a.rows()).hasSize(2);
        assertThat(a.totals().billVarianceTotal()).isEqualByComparingTo("20");

        PurchasePriceVarianceDto beta = query.report(company.getId(), FROM, TO, null, supplier2.uid(), true);
        assertThat(beta.rows()).as("billed at the order price; the draft bill does not count").isEmpty();
    }

    @Test
    void nonRootClerk_isRefusedABranchTheyAreNotAssignedTo() {
        asClerk();
        assertThatCode(() -> query.report(company.getId(), FROM, TO, branchA.getUid(), null, true))
                .doesNotThrowAnyException();
        assertThatCode(() -> query.report(company.getId(), FROM, TO, null, null, true))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> query.report(company.getId(), FROM, TO, branchB.getUid(), null, true))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(BranchReadGuard.branchNotAssigned().getMessage());
    }
}
