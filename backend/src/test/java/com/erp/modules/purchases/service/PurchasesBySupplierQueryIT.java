package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.dto.PurchasesBySupplierDto;
import com.erp.modules.purchases.domain.dto.PurchasesBySupplierRowDto;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.security.BranchReadGuard;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link PurchasesBySupplierQuery} against real Postgres.
 *
 * <p>Fixture (March 2026):
 * <pre>
 *   Alpha  GR1 branch A  X 10 @ 100 = 1000  05-Mar;  return 3 of it (300) confirmed 18-Mar
 *          GR5 branch B  X  2 @ 100 =  200  12-Mar
 *          bill  branch A  10 @ 105 = 1050 net, dated 25-Mar, 500 unpaid
 *          DRAFT bill 26-Mar, and a bill dated 02-Apr — both must be ignored
 *   Beta   GR3 branch A  X  4 @  55 =  220  DIRECT 15-Mar
 *          GR4 branch A  Y  3 @  40 =  120  received 25-Feb, VOIDED 02-Mar  ->  -120 in March
 * </pre>
 * Alpha: 2 receipts, 1200 received, 300 returns, 900 net, 1050 billed, 500 unpaid.
 * Beta: 1 receipt, 100 received (220 - 120), nothing returned or billed.
 */
class PurchasesBySupplierQueryIT extends PurchaseReportFixture {

    private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate TO   = LocalDate.of(2026, 3, 31);

    @Autowired private PurchasesBySupplierQuery query;

    @BeforeEach
    void seed() {
        ProductDto x = product("BySupp X");
        ProductDto y = product("BySupp Y");

        PurchaseOrderDto po1 = placeOrder(supplier1, "TZS", new Object[] {x.uid(), "10", "100"});
        GoodsReceiptDto gr1 = receive(po1, 0, "10");
        setReceivedAt(gr1, LocalDate.of(2026, 3, 5));
        confirmedReturn(gr1, "3", LocalDate.of(2026, 3, 18));
        bill(supplier1, branchA, gr1, "10", "105", LocalDate.of(2026, 3, 25), "500", "APPROVED");
        bill(supplier1, branchA, gr1, "1", "999", LocalDate.of(2026, 3, 26), "1178.82", "DRAFT");
        bill(supplier1, branchA, gr1, "1", "777", LocalDate.of(2026, 4, 2), "916.86", "APPROVED");

        GoodsReceiptDto gr3 = receiveDirect(supplier2, x, "4", "55");
        setReceivedAt(gr3, LocalDate.of(2026, 3, 15));

        PurchaseOrderDto po2 = placeOrder(supplier2, "TZS", new Object[] {y.uid(), "3", "40"});
        GoodsReceiptDto gr4 = receive(po2, 0, "3");
        voidReceipt(gr4);
        setReceivedAt(gr4, LocalDate.of(2026, 2, 25));
        setVoidedAt(gr4, LocalDate.of(2026, 3, 2));

        asRoot(branchB);
        PurchaseOrderDto po3 = placeOrder(supplier1, "TZS", new Object[] {x.uid(), "2", "100"});
        GoodsReceiptDto gr5 = receive(po3, 0, "2");
        setReceivedAt(gr5, LocalDate.of(2026, 3, 12));
        asRoot(branchA);
    }

    @Test
    void allColumns_perSupplierAndTotals() {
        PurchasesBySupplierDto r = query.report(company.getId(), FROM, TO, null, true, true);

        assertThat(r.rows()).hasSize(2);
        PurchasesBySupplierRowDto alpha = row(r, "Alpha Traders");
        assertThat(alpha.receipts()).isEqualTo(2);
        assertThat(alpha.receivedValue()).isEqualByComparingTo("1200");
        assertThat(alpha.returnsValue()).isEqualByComparingTo("300");
        assertThat(alpha.netPurchases()).isEqualByComparingTo("900");
        assertThat(alpha.billedAmount()).isEqualByComparingTo("1050");
        assertThat(alpha.unpaidAmount()).isEqualByComparingTo("500");

        PurchasesBySupplierRowDto beta = row(r, "Beta Wholesale");
        assertThat(beta.receipts()).isEqualTo(1);
        assertThat(beta.receivedValue()).isEqualByComparingTo("100");
        assertThat(beta.returnsValue()).isEqualByComparingTo("0");
        assertThat(beta.netPurchases()).isEqualByComparingTo("100");
        assertThat(beta.billedAmount()).isEqualByComparingTo("0");

        // Largest first.
        assertThat(r.rows().get(0).supplierName()).isEqualTo("Alpha Traders");

        assertThat(r.totals().receipts()).isEqualTo(3);
        assertThat(r.totals().receivedValue()).isEqualByComparingTo("1300");
        assertThat(r.totals().returnsValue()).isEqualByComparingTo("300");
        assertThat(r.totals().netPurchases()).isEqualByComparingTo("1000");
        assertThat(r.totals().billedAmount()).isEqualByComparingTo("1050");
        assertThat(r.totals().unpaidAmount()).isEqualByComparingTo("500");
        assertThat(r.totals().rowsInOtherCurrency()).isZero();
        assertThat(r.returnsShown()).isTrue();
        assertThat(r.billsShown()).isTrue();
    }

    @Test
    void columnsTheCallerMayNotSee_areNull_notZero() {
        PurchasesBySupplierDto r = query.report(company.getId(), FROM, TO, null, false, false);

        assertThat(r.returnsShown()).isFalse();
        assertThat(r.billsShown()).isFalse();
        assertThat(r.rows()).allSatisfy(row -> {
            assertThat(row.returnsValue()).isNull();
            assertThat(row.netPurchases()).isNull();
            assertThat(row.billedAmount()).isNull();
            assertThat(row.unpaidAmount()).isNull();
        });
        assertThat(r.totals().receivedValue()).isEqualByComparingTo("1300");
        assertThat(r.totals().returnsValue()).isNull();
        assertThat(r.totals().netPurchases()).isNull();
        assertThat(r.totals().billedAmount()).isNull();
        assertThat(r.totals().unpaidAmount()).isNull();
    }

    @Test
    void aSupplierBilledButNotDeliveredInThePeriod_stillGetsARow() {
        PurchasesBySupplierDto april = query.report(company.getId(),
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), null, true, true);
        assertThat(april.rows()).hasSize(1);
        PurchasesBySupplierRowDto alpha = april.rows().get(0);
        assertThat(alpha.receipts()).isZero();
        assertThat(alpha.receivedValue()).isEqualByComparingTo("0");
        assertThat(alpha.billedAmount()).isEqualByComparingTo("777");
    }

    @Test
    void branchFilter_narrowsReceiptsReturnsAndBills() {
        PurchasesBySupplierDto a = query.report(company.getId(), FROM, TO, branchA.getUid(), true, true);
        PurchasesBySupplierRowDto alpha = row(a, "Alpha Traders");
        assertThat(alpha.receivedValue()).isEqualByComparingTo("1000");
        assertThat(alpha.returnsValue()).isEqualByComparingTo("300");
        assertThat(alpha.billedAmount()).isEqualByComparingTo("1050");
        assertThat(a.totals().receivedValue()).isEqualByComparingTo("1100");

        PurchasesBySupplierDto b = query.report(company.getId(), FROM, TO, branchB.getUid(), true, true);
        assertThat(b.rows()).hasSize(1);
        assertThat(b.rows().get(0).receivedValue()).isEqualByComparingTo("200");
        assertThat(b.rows().get(0).returnsValue()).isEqualByComparingTo("0");
        assertThat(b.rows().get(0).billedAmount()).isEqualByComparingTo("0");
        assertThat(b.branchName()).isEqualTo("Branch B");
    }

    @Test
    void nonRootClerk_isRefusedABranchTheyAreNotAssignedTo() {
        asClerk();
        assertThatCode(() -> query.report(company.getId(), FROM, TO, branchA.getUid(), true, true))
                .doesNotThrowAnyException();
        assertThatCode(() -> query.report(company.getId(), FROM, TO, null, true, true))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> query.report(company.getId(), FROM, TO, branchB.getUid(), true, true))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(BranchReadGuard.branchNotAssigned().getMessage());
    }

    private static PurchasesBySupplierRowDto row(PurchasesBySupplierDto r, String name) {
        return r.rows().stream().filter(x -> name.equals(x.supplierName())).findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + name));
    }
}
