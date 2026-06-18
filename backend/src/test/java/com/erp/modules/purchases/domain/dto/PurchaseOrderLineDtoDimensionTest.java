package com.erp.modules.purchases.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.purchases.domain.entity.PurchaseOrder;
import com.erp.modules.purchases.domain.entity.PurchaseOrderLine;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test (no Spring / no DB) verifying {@link PurchaseOrderLineDto#from} exposes the
 * ADR-0041 D4 per-line dimension tags so AP can copy them onto a derived bill line across the
 * service boundary (NFR-AP-06 — no Purchases entity import in AP).
 */
class PurchaseOrderLineDtoDimensionTest {

    private static PurchaseOrderLine line() {
        PurchaseOrder po = new PurchaseOrder(10L, 20L, 30L, "SUP", "Supplier", "TZS", 1L);
        return new PurchaseOrderLine(
                po, (short) 1, 100L, "P-1", "Product 1", 200L, "PCS",
                new BigDecimal("5"), new BigDecimal("5"),
                new BigDecimal("10.0000"), new BigDecimal("50.0000"), 1L);
    }

    @Test
    void from_exposesDimensionTags_whenSet() {
        PurchaseOrderLine l = line();
        l.setCostCentreValueId(111L);
        l.setDepartmentValueId(222L);

        PurchaseOrderLineDto dto = PurchaseOrderLineDto.from(l);

        assertThat(dto.costCentreValueId()).isEqualTo(111L);
        assertThat(dto.departmentValueId()).isEqualTo(222L);
    }

    @Test
    void from_dimensionTagsNull_whenUntagged() {
        PurchaseOrderLineDto dto = PurchaseOrderLineDto.from(line());

        assertThat(dto.costCentreValueId()).isNull();
        assertThat(dto.departmentValueId()).isNull();
    }
}
