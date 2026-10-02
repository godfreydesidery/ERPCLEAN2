package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.stock.domain.dto.ReorderReportDto;
import com.erp.modules.stock.domain.dto.ReorderRowDto;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.BranchReadGuard;
import com.erp.support.ReportQueryTestBase;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link ReorderReportQuery} against real Postgres: lines at or below their reorder level, the
 * suggested quantity, the last receipt cost shown only when entitled, and the branch / supplier
 * filters resolved within the company.
 */
class ReorderReportQueryIT extends ReportQueryTestBase {

    @Autowired private ReorderReportQuery query;

    private String s1Uid;

    @BeforeEach
    void seedStock() {
        long co = company.getId();
        long own = ownBranch.getId();
        long other = otherBranch.getId();
        long unit = seed.unit(co, "PCS");
        long l1 = seed.location(co, own, "MAIN");
        long o1 = seed.location(co, other, "MAIN2");
        long s1 = seed.supplier(co, "S1", "First Supplier");
        long s2 = seed.supplier(co, "S2", "Second Supplier");
        s1Uid = seed.uidOf("suppliers", s1);

        // A — below its level, with a maximum: fill to 50. Last receipt cost 110.
        long a = seed.product(co, unit, "A", "Low widget", s1);
        seed.movement(co, own, l1, a, "GOODS_RECEIPT", bd("20"), at(100, 9), bd("90"), bd("1800"), null);
        seed.movement(co, other, o1, a, "GOODS_RECEIPT", bd("5"), at(45, 9), bd("110"), bd("550"), null);
        seed.onHand(co, own, l1, a, bd("25"), bd("2500"), bd("100"), bd("30"), bd("50"));

        // B — exactly AT its level: listed, shortfall 0, order size from the product (24).
        long b = seed.product(co, unit, "B", "At-level item", s1);
        jdbc.update("UPDATE products SET reorder_qty = 24 WHERE id = ?", b);
        seed.movement(co, own, l1, b, "GOODS_RECEIPT", bd("30"), at(10, 9), bd("5"), bd("150"), null);
        seed.onHand(co, own, l1, b, bd("30"), bd("150"), bd("5"), bd("30"), null);

        // C — no level set: never listed. Z — above its level: not listed.
        long c = seed.product(co, unit, "C", "No level", s1);
        seed.onHand(co, own, l1, c, bd("1"), bd("10"), bd("10"), null, null);
        long z = seed.product(co, unit, "Z", "Plenty", s1);
        seed.onHand(co, own, l1, z, bd("99"), bd("990"), bd("10"), bd("10"), null);

        // E — other branch, below level, never received: no last cost.
        long e = seed.product(co, unit, "E", "Never received", s2);
        seed.onHand(co, other, o1, e, bd("1"), null, null, bd("5"), null);

        // X — archived: not reordered even though it is out.
        long x = seed.product(co, unit, "X", "Discontinued", s2);
        jdbc.update("UPDATE products SET status = 'ARCHIVED' WHERE id = ?", x);
        seed.onHand(co, own, l1, x, bd("0"), null, null, bd("5"), null);
    }

    @Test
    void listsLinesAtOrBelowLevel_withSuggestionAndLastCost() {
        ReorderReportDto r = query.report(company.getId(), null, null, true);

        assertThat(r.rows()).extracting(ReorderRowDto::productCode)
                .containsExactlyInAnyOrder("A", "B", "E");
        assertThat(r.costVisible()).isTrue();

        ReorderRowDto a = row(r, "A");
        assertThat(a.shortfall()).isEqualByComparingTo("5");
        assertThat(a.suggestedOrderQty()).as("fill to the maximum 50").isEqualByComparingTo("25");
        assertThat(a.supplierName()).isEqualTo("First Supplier");
        assertThat(a.lastCost()).as("the most recent receipt, any branch").isEqualByComparingTo("110");
        assertThat(a.estimatedOrderValue()).isEqualByComparingTo("2750");

        ReorderRowDto b = row(r, "B");
        assertThat(b.shortfall()).isEqualByComparingTo("0");
        assertThat(b.suggestedOrderQty()).isEqualByComparingTo("24");
        assertThat(b.estimatedOrderValue()).isEqualByComparingTo("120");

        ReorderRowDto e = row(r, "E");
        assertThat(e.branchName()).isEqualTo("Other Branch");
        assertThat(e.lastCost()).isNull();
        assertThat(e.estimatedOrderValue()).isNull();

        assertThat(r.itemCount()).isEqualTo(3);
        assertThat(r.estimatedOrderValue()).isEqualByComparingTo("2870");
        assertThat(r.rowsWithoutCost()).isEqualTo(1);
    }

    @Test
    void withoutValuationRights_theCostColumnsAreWithheld() {
        ReorderReportDto r = query.report(company.getId(), null, null, false);

        assertThat(r.costVisible()).isFalse();
        assertThat(r.rows()).hasSize(3);
        assertThat(r.rows()).allMatch(x -> x.lastCost() == null && x.estimatedOrderValue() == null);
        assertThat(r.estimatedOrderValue()).isNull();
        assertThat(r.rowsWithoutCost()).isZero();
    }

    @Test
    void supplierAndBranchFilters_narrow() {
        assertThat(query.report(company.getId(), null, s1Uid, true).rows())
                .extracting(ReorderRowDto::productCode).containsExactlyInAnyOrder("A", "B");
        ReorderReportDto own = query.report(company.getId(), ownBranch.getUid(), null, true);
        assertThat(own.branchName()).isEqualTo("Own Branch");
        assertThat(own.rows()).extracting(ReorderRowDto::productCode)
                .containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void nonRootClerk_isRefusedABranchTheyAreNotAssignedTo() {
        asClerk();
        assertThatCode(() -> query.report(company.getId(), ownBranch.getUid(), null, false))
                .doesNotThrowAnyException();
        assertThatCode(() -> query.report(company.getId(), null, null, false))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> query.report(company.getId(), otherBranch.getUid(), null, false))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(BranchReadGuard.branchNotAssigned().getMessage());
    }

    @Test
    void unknownBranchOrSupplier_isNotFound() {
        assertThatThrownBy(() -> query.report(company.getId(), "NOSUCHBRANCHUID0000000000", null, true))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> query.report(company.getId(), null, "NOSUCHSUPPLIER00000000000", true))
                .isInstanceOf(NotFoundException.class);
    }

    private static ReorderRowDto row(ReorderReportDto r, String code) {
        return r.rows().stream().filter(x -> code.equals(x.productCode())).findFirst()
                .orElseThrow(() -> new AssertionError("no row " + code));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
