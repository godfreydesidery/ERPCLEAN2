package com.erp.modules.ar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ar.domain.dto.RecordReceiptRequest;
import com.erp.modules.ar.domain.dto.RecordReceiptRequest.AllocationLineRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ARC-20: blank allocations used to be documented as "on account" while the service applied the
 * money oldest-first. The mode is now explicit; absent keeps the old server rule for old callers.
 */
class ArReceiptAllocationModeTest {

    private static final List<AllocationLineRequest> LINES =
            List.of(new AllocationLineRequest("INV1", new BigDecimal("10")));

    private static RecordReceiptRequest req(List<AllocationLineRequest> lines, String mode) {
        return new RecordReceiptRequest("CO", "CU", new BigDecimal("100"), "TZS",
                LocalDate.of(2026, 10, 10), "CASH", null, lines, null, null, null, null, mode);
    }

    @Test
    void absentModeKeepsTheHistoricalRule() {
        assertThat(ArReceiptServiceImpl.resolveAllocationMode(req(List.of(), null))).isEqualTo("AUTO");
        assertThat(ArReceiptServiceImpl.resolveAllocationMode(req(null, " "))).isEqualTo("AUTO");
        assertThat(ArReceiptServiceImpl.resolveAllocationMode(req(LINES, null))).isEqualTo("MANUAL");
    }

    @Test
    void explicitModesAreHonoured() {
        assertThat(ArReceiptServiceImpl.resolveAllocationMode(req(List.of(), "manual"))).isEqualTo("MANUAL");
        assertThat(ArReceiptServiceImpl.resolveAllocationMode(req(LINES, "MANUAL"))).isEqualTo("MANUAL");
        assertThat(ArReceiptServiceImpl.resolveAllocationMode(req(List.of(), "ON_ACCOUNT"))).isEqualTo("ON_ACCOUNT");
        assertThat(ArReceiptServiceImpl.resolveAllocationMode(req(null, "AUTO"))).isEqualTo("AUTO");
    }

    @Test
    void contradictoryOrUnknownModesAreRefusedInPlainWords() {
        assertThatThrownBy(() -> ArReceiptServiceImpl.resolveAllocationMode(req(LINES, "ON_ACCOUNT")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chosen by hand");
        assertThatThrownBy(() -> ArReceiptServiceImpl.resolveAllocationMode(req(LINES, "AUTO")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArReceiptServiceImpl.resolveAllocationMode(req(null, "OLDEST")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keep it all on account");
    }
}
