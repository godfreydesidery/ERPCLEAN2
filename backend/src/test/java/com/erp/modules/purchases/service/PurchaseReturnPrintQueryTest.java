package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.purchases.domain.dto.PurchaseReturnPrintDto;
import com.erp.modules.purchases.domain.dto.PurchaseReturnPrintLineDto;
import com.erp.modules.purchases.service.PurchaseReturnPrintQuery.Header;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The printed purchase return's read model, without a database: the date the note carries, the
 * line figures it prints, and the foot. The SQL behind it is covered by PurchasesServiceImplIT.
 */
class PurchaseReturnPrintQueryTest {

    /** 22:30 UTC on 31 Jan is 01:30 on 1 Feb in Dar es Salaam — the note carries the local date. */
    @Test
    void returnDateIsFormattedInTheCompanysTimeZone() {
        PurchaseReturnPrintDto dto = PurchaseReturnPrintQuery.assemble(
                header("CONFIRMED", Instant.parse("2026-01-20T08:00:00Z"),
                        Instant.parse("2026-01-31T22:30:00Z"), "Africa/Dar_es_Salaam",
                        "10000", "0", "10000"),
                List.of());

        assertThat(dto.returnDate()).isEqualTo("01-Feb-2026");
        assertThat(dto.returnedAt()).isEqualTo(Instant.parse("2026-01-31T22:30:00Z"));
        assertThat(dto.timeZone()).isEqualTo("Africa/Dar_es_Salaam");
    }

    /** A DRAFT has no confirm time: the date is when it was raised. A bad zone never fails a print. */
    @Test
    void draftUsesCreatedAtAndABadZoneFallsBack() {
        PurchaseReturnPrintDto dto = PurchaseReturnPrintQuery.assemble(
                header("DRAFT", Instant.parse("2026-03-05T10:00:00Z"), null, "Not/AZone",
                        "500", "0", "500"),
                List.of());

        assertThat(dto.returnDate()).isEqualTo("05-Mar-2026");
        assertThat(dto.timeZone()).isEqualTo("Africa/Dar_es_Salaam");
        assertThat(dto.isDraft()).isTrue();
    }

    /** Lines print in the line's own unit — 2 CRATE at the crate cost — never the base quantity. */
    @Test
    void linesStayInTheLinesUnitAndTotalsAreTheStoredFiguresAtTwoDecimals() {
        PurchaseReturnPrintLineDto crate = new PurchaseReturnPrintLineDto(
                1, "BEER-01", "Lager 500ml", "CRATE",
                new BigDecimal("2.000000"), new BigDecimal("36000.0000"),
                new BigDecimal("72000.0000"));

        PurchaseReturnPrintDto dto = PurchaseReturnPrintQuery.assemble(
                header("CONFIRMED", Instant.parse("2026-04-01T06:00:00Z"),
                        Instant.parse("2026-04-02T06:00:00Z"), null,
                        "72000.0000", "12960.0000", "84960.0000"),
                List.of(crate));

        PurchaseReturnPrintLineDto line = dto.lines().get(0);
        assertThat(line.unitName()).isEqualTo("CRATE");
        assertThat(line.returnedQty()).isEqualByComparingTo("2");
        assertThat(line.unitCost()).isEqualByComparingTo("36000");
        assertThat(line.lineValue()).isEqualByComparingTo("72000");

        assertThat(dto.netAmount()).isEqualTo(new BigDecimal("72000.00"));
        assertThat(dto.vatAmount()).isEqualTo(new BigDecimal("12960.00"));
        assertThat(dto.totalAmount()).isEqualTo(new BigDecimal("84960.00"));
        assertThat(dto.hasVat()).isTrue();
    }

    /** Reference numbers, supplier identity and the address assembled from the parts present. */
    @Test
    void carriesTheReferencesAndSupplierIdentity() {
        PurchaseReturnPrintDto dto = PurchaseReturnPrintQuery.assemble(
                header("CONFIRMED", Instant.parse("2026-04-01T06:00:00Z"),
                        Instant.parse("2026-04-02T06:00:00Z"), null, "100", "0", "100"),
                List.of());

        assertThat(dto.goodsReceiptNumber()).isEqualTo("GRN-0007");
        assertThat(dto.purchaseOrderNumber()).isEqualTo("PO-0003");
        assertThat(dto.debitNoteNumber()).isEqualTo("DN-0001");
        assertThat(dto.supplierName()).isEqualTo("Kondiki Ltd");
        assertThat(dto.supplierTin()).isEqualTo("109-876-543");
        assertThat(dto.supplierVrn()).isEqualTo("40-012345-K");
        assertThat(dto.supplierAddressLines()).containsExactly("Plot 7", "Moshi, Kilimanjaro", "TZ");
        assertThat(dto.hasVat()).isFalse();
    }

    // -------------------------------------------------------------------------

    private static Header header(String status, Instant createdAt, Instant confirmedAt, String tz,
                                 String net, String vat, String gross) {
        return new Header(1L, "01J00000000000000000000PRT", 9L, "PRET-0001", status,
                "Damaged in transit", new BigDecimal(net), new BigDecimal(vat), new BigDecimal(gross),
                "TZS", createdAt, confirmedAt,
                "Kondiki (snapshot)", "GRN-0007", "PO-0003", "Moshi Branch",
                "Kondiki Ltd", " 109-876-543 ", "40-012345-K",
                "Plot 7", "Moshi", "Kilimanjaro", "TZ",
                "DN-0001", "Richard", tz);
    }
}
