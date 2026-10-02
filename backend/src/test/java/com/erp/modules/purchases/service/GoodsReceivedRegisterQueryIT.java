package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;
import com.erp.modules.purchases.domain.dto.GoodsReceivedRegisterDto;
import com.erp.modules.purchases.domain.dto.GoodsReceivedRegisterRowDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.BranchReadGuard;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link GoodsReceivedRegisterQuery} against real Postgres: the receipt/void UNION, period windows,
 * every filter, paging with whole-set totals, direct receipts, and the branch guard for a non-root
 * caller.
 *
 * <p>Fixture (all March 2026 unless said otherwise):
 * <pre>
 *   GR1  Alpha  branch A  X 10 @ 100 = 1000   received 05-Mar
 *   GR2  Alpha  branch A  Y  5 @  40 =  200   received 10-Mar, VOIDED 20-Mar
 *   GR5  Alpha  branch B  X  2 @ 100 =  200   received 12-Mar
 *   GR3  Beta   branch A  X  4 @  55 =  220   DIRECT receipt, received 15-Mar
 *   GR4  Beta   branch A  Y  3 @  40 =  120   received 25-Feb (outside), VOIDED 02-Mar
 * </pre>
 * March register: +1000 +200 +200 +220 -120 -200 = 1300 over 6 lines (4 receipts, 2 voids).
 */
class GoodsReceivedRegisterQueryIT extends PurchaseReportFixture {

    private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate TO   = LocalDate.of(2026, 3, 31);

    @Autowired private GoodsReceivedRegisterQuery query;

    private ProductDto x;
    private ProductDto y;
    private GoodsReceiptDto gr2;

    @BeforeEach
    void seed() {
        x = product("Register X");
        y = product("Register Y");

        PurchaseOrderDto po1 = placeOrder(supplier1, "TZS", new Object[] {x.uid(), "10", "100"},
                new Object[] {y.uid(), "5", "40"});
        GoodsReceiptDto gr1 = receive(po1, 0, "10");
        setReceivedAt(gr1, LocalDate.of(2026, 3, 5));
        gr2 = receive(po1, 1, "5");
        voidReceipt(gr2);
        setReceivedAt(gr2, LocalDate.of(2026, 3, 10));
        setVoidedAt(gr2, LocalDate.of(2026, 3, 20));

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
    void wholeCompany_listsReceiptsAndVoidReversals_andTotalsNet() {
        GoodsReceivedRegisterDto r = query.report(company.getId(), FROM, TO, null, null, null, 0, 50);

        assertThat(r.rows()).extracting(GoodsReceivedRegisterRowDto::entryType)
                .containsExactly("VOID", "RECEIPT", "RECEIPT", "RECEIPT", "RECEIPT", "VOID");
        assertThat(r.rows()).extracting(GoodsReceivedRegisterRowDto::value)
                .usingElementComparator(java.math.BigDecimal::compareTo)
                .containsExactly(bd("-120"), bd("1000"), bd("200"), bd("200"), bd("220"), bd("-200"));

        // The void reversal of GR2 carries GR2's number and negated quantity.
        GoodsReceivedRegisterRowDto gr2Void = r.rows().get(5);
        assertThat(gr2Void.receiptNumber()).isEqualTo(gr2.receiptNumber());
        assertThat(gr2Void.quantity()).isEqualByComparingTo("-5");
        assertThat(gr2Void.unitCost()).isEqualByComparingTo("40");
        assertThat(gr2Void.entryAt()).startsWith("2026-03-20");

        // The direct receipt is flagged; ordinary receipts are not.
        GoodsReceivedRegisterRowDto direct = r.rows().get(4);
        assertThat(direct.direct()).isTrue();
        assertThat(direct.supplierName()).isEqualTo("Beta Wholesale");
        assertThat(r.rows().get(1).direct()).isFalse();
        assertThat(r.rows().get(1).orderNumber()).isNotBlank();
        assertThat(r.rows().get(3).branchName()).isEqualTo("Branch B");

        assertThat(r.totals().value()).isEqualByComparingTo("1300");
        assertThat(r.totals().receipts()).isEqualTo(4);
        assertThat(r.totals().voids()).isEqualTo(2);
        assertThat(r.totals().lines()).isEqualTo(6);
        assertThat(r.totals().rowsInOtherCurrency()).isZero();
        assertThat(r.currency()).isEqualTo("TZS");
        assertThat(r.company().name()).isEqualTo("PurchRpt IT Co");
    }

    @Test
    void periodBoundaries_receiptInFebruaryIsOut_butItsMarchVoidIsIn() {
        GoodsReceivedRegisterDto feb = query.report(company.getId(),
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), null, null, null, 0, 50);
        assertThat(feb.rows()).hasSize(1);
        assertThat(feb.rows().get(0).entryType()).isEqualTo("RECEIPT");
        assertThat(feb.totals().value()).isEqualByComparingTo("120");

        // A single-day window takes that whole day in the company's zone.
        GoodsReceivedRegisterDto day = query.report(company.getId(),
                LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 5), null, null, null, 0, 50);
        assertThat(day.rows()).hasSize(1);
        assertThat(day.totals().value()).isEqualByComparingTo("1000");
    }

    @Test
    void filters_branchSupplierProduct() {
        assertThat(query.report(company.getId(), FROM, TO, branchA.getUid(), null, null, 0, 50)
                .totals().value()).isEqualByComparingTo("1100");
        assertThat(query.report(company.getId(), FROM, TO, branchB.getUid(), null, null, 0, 50)
                .totals().value()).isEqualByComparingTo("200");

        GoodsReceivedRegisterDto beta = query.report(company.getId(), FROM, TO, null, supplier2.uid(), null, 0, 50);
        assertThat(beta.totals().value()).isEqualByComparingTo("100");   // 220 direct - 120 void
        assertThat(beta.supplierName()).isEqualTo("Beta Wholesale");

        GoodsReceivedRegisterDto onlyX = query.report(company.getId(), FROM, TO, null, null, x.uid(), 0, 50);
        assertThat(onlyX.totals().value()).isEqualByComparingTo("1420");  // 1000 + 200 + 220
        assertThat(onlyX.rows()).allMatch(row -> "RECEIPT".equals(row.entryType()));
    }

    @Test
    void paging_footerCoversTheWholeSet() {
        GoodsReceivedRegisterDto page1 = query.report(company.getId(), FROM, TO, null, null, null, 1, 2);
        assertThat(page1.rows()).hasSize(2);
        assertThat(page1.rows()).extracting(GoodsReceivedRegisterRowDto::value)
                .usingElementComparator(java.math.BigDecimal::compareTo)
                .containsExactly(bd("200"), bd("200"));
        assertThat(page1.totalElements()).isEqualTo(6);
        assertThat(page1.totalPages()).isEqualTo(3);
        assertThat(page1.totals().value()).isEqualByComparingTo("1300");
    }

    @Test
    void foreignFilterUid_isNotFound_neverAWidenedFilter() {
        assertThatThrownBy(() -> query.report(company.getId(), FROM, TO, null,
                "NOSUCHSUPPLIER000000000000", null, 0, 50))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> query.report(company.getId(), TO, FROM, null, null, null, 0, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonRootClerk_isRefusedABranchTheyAreNotAssignedTo() {
        asClerk();
        assertThatCode(() -> query.report(company.getId(), FROM, TO, branchA.getUid(), null, null, 0, 50))
                .doesNotThrowAnyException();
        assertThatCode(() -> query.report(company.getId(), FROM, TO, null, null, null, 0, 50))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> query.report(company.getId(), FROM, TO, branchB.getUid(), null, null, 0, 50))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(BranchReadGuard.branchNotAssigned().getMessage());
        assertThatThrownBy(() -> query.reportForExport(company.getId(), FROM, TO, branchB.getUid(), null, null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void export_returnsEveryRow() {
        GoodsReceivedRegisterDto all = query.reportForExport(company.getId(), FROM, TO, null, null, null);
        assertThat(all.rows()).hasSize(6);
        List<GoodsReceivedRegisterRowDto> rows = all.rows();
        assertThat(rows.get(0).entryAt()).startsWith("2026-03-02");
    }

    private static java.math.BigDecimal bd(String v) {
        return new java.math.BigDecimal(v);
    }
}
