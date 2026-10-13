package com.erp.modules.ar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
import org.junit.jupiter.api.Test;

/** The Receivables status filter (ARC-02): what the screen sends maps to the status set. */
class ArInvoiceStatusFilterTest {

    @Test
    void blankMeansEveryStatus() {
        assertThat(ArInvoiceServiceImpl.parseStatuses(null)).isEmpty();
        assertThat(ArInvoiceServiceImpl.parseStatuses("  ")).isEmpty();
    }

    @Test
    void oneStatus_caseInsensitive() {
        assertThat(ArInvoiceServiceImpl.parseStatuses("open"))
                .containsExactly(ArInvoiceStatus.OPEN);
    }

    @Test
    void commaSeparatedList() {
        assertThat(ArInvoiceServiceImpl.parseStatuses("OPEN, PARTIAL,"))
                .containsExactlyInAnyOrder(ArInvoiceStatus.OPEN, ArInvoiceStatus.PARTIAL);
    }

    @Test
    void unknownWord_isAFriendlyError() {
        assertThatThrownBy(() -> ArInvoiceServiceImpl.parseStatuses("UNPAID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown invoice status. Choose Open, Partial, Paid or Written off.");
    }
}
