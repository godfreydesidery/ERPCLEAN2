package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.purchases.domain.dto.PurchaseReturnPrintDto;
import com.erp.modules.purchases.domain.dto.PurchaseReturnPrintLineDto;
import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.export.TabularRenderModel;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * The Excel / CSV face of the purchase return export, and the endpoint's gate.
 */
class PurchaseReturnExportFlattenTest {

    private static final ZonedDateTime NOW =
            ZonedDateTime.of(2026, 10, 10, 9, 30, 0, 0, ZoneId.of("Africa/Dar_es_Salaam"));

    @Test
    void headerCarriesLetterheadReferencesAndSupplierIdentity() {
        TabularRenderModel m = PurchaseReturnController.flatten(sample("CONFIRMED", "0.00"),
                new ExportLetterhead.Letterhead(company(), null), NOW);

        String header = String.join("\n", m.headerLines());
        assertThat(header).contains("Mwondoko Traders Ltd", "TIN: 100-200-300", "VRN: 40-999999-A");
        assertThat(header).contains("Return No: PRET-0012", "Date: 02-Oct-2026", "Status: CONFIRMED");
        assertThat(header).contains("GRN No: GRN-0007", "PO No: PO-0003", "Debit Note No: DN-0004");
        assertThat(header).contains("Supplier: KONDIKI", "TIN: 109-876-543", "VRN: 40-012345-K");
        assertThat(header).contains("Reason: Damaged in transit");
        assertThat(m.title()).isEqualTo("Purchase Return PRET-0012");
    }

    @Test
    void rowsAreInTheLinesUnitAndTheFootCarriesTotalsAndSignOff() {
        TabularRenderModel m = PurchaseReturnController.flatten(sample("CONFIRMED", "0.00"),
                new ExportLetterhead.Letterhead(company(), null), NOW);

        assertThat(m.rows()).containsExactly(
                List.of("1", "BEER-01", "Lager 500ml", "CRATE", "2", "36,000.00", "72,000.00"));
        assertThat(m.totalsRow()).last().isEqualTo("72,000.00");

        String footer = String.join("\n", m.footerLines());
        assertThat(footer).contains("Net Amount: 72,000.00", "Total Amount: 72,000.00");
        assertThat(footer).doesNotContain("VAT Amount");
        assertThat(footer).contains("Prepared By: RICHARD", "Received by Supplier:");
        assertThat(footer).contains("Printed On: 10-Oct-2026");
    }

    @Test
    void vatLineOnlyWhenPresentAndDraftIsFlagged() {
        TabularRenderModel m = PurchaseReturnController.flatten(sample("DRAFT", "12960.00"),
                new ExportLetterhead.Letterhead(company(), null), NOW);

        assertThat(String.join("\n", m.footerLines())).contains("VAT Amount: 12,960.00");
        assertThat(String.join("\n", m.headerLines())).contains("Status: DRAFT (not confirmed)");
    }

    @Test
    void quantityPrintsWithoutTrailingZeros() {
        assertThat(PurchaseReturnController.fmtQty(new BigDecimal("2.000000"))).isEqualTo("2");
        assertThat(PurchaseReturnController.fmtQty(new BigDecimal("1.500000"))).isEqualTo("1.5");
        assertThat(PurchaseReturnController.fmtQty(new BigDecimal("1E+2"))).isEqualTo("100");
        assertThat(PurchaseReturnController.fmtQty(null)).isEmpty();
    }

    /** Same gate as viewing the return plus the GRN print permission — no new code. */
    @Test
    void exportIsGatedOnViewPlusDocumentRender() throws NoSuchMethodException {
        Method m = PurchaseReturnController.class.getMethod("export", String.class,
                com.erp.modules.reporting.domain.enums.ExportFormat.class);
        String gate = m.getAnnotation(PreAuthorize.class).value();
        assertThat(gate).contains("@perm.scoped(#uid, 'purchasereturn', 'PURCHASE.RETURN.VIEW')");
        assertThat(gate).contains("@perm.has('DOCUMENT.RENDER')");
    }

    // -------------------------------------------------------------------------

    private static ReportCompanyHeaderDto company() {
        return new ReportCompanyHeaderDto("Mwondoko Traders Ltd", null, "Plot 7", null, "Moshi",
                null, "TZ", null, null, "100-200-300", "40-999999-A");
    }

    private static PurchaseReturnPrintDto sample(String status, String vat) {
        BigDecimal net = new BigDecimal("72000.00");
        return new PurchaseReturnPrintDto(
                "01J00000000000000000000PRT", 1L, "PRET-0012", status,
                "02-Oct-2026", Instant.parse("2026-10-02T07:00:00Z"),
                "GRN-0007", "PO-0003", "MWONDOKO",
                "KONDIKI", "109-876-543", "40-012345-K",
                List.of("P.O. Box 1234"),
                "Damaged in transit", "TZS", "DRAFT".equals(status) ? null : "DN-0004", "RICHARD",
                List.of(new PurchaseReturnPrintLineDto(1, "BEER-01", "Lager 500ml", "CRATE",
                        new BigDecimal("2.000000"), new BigDecimal("36000.0000"),
                        new BigDecimal("72000.0000"))),
                net, new BigDecimal(vat), net.add(new BigDecimal(vat)),
                "Africa/Dar_es_Salaam");
    }
}
