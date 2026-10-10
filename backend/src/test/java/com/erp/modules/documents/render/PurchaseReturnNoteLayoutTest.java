package com.erp.modules.documents.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.documents.domain.entity.DocumentBranding;
import com.erp.modules.documents.service.DocumentModelBuilder;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.purchases.domain.dto.PurchaseReturnPrintDto;
import com.erp.modules.purchases.domain.dto.PurchaseReturnPrintLineDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The printed purchase return / debit note (Kilimanjaro: "cannot export or print purchase return")
 * through the same builder + renderer pair as the GRN, read back out of the actual PDF.
 */
class PurchaseReturnNoteLayoutTest {

    private final DocumentModelBuilder builder =
            new DocumentModelBuilder(new ObjectMapper(), org.mockito.Mockito.mock(CompanyRepository.class), com.erp.platform.common.time.CompanyCalendar.fixed(com.erp.platform.common.time.BusinessZone.DEFAULT, java.time.Clock.systemUTC()));
    private final DocumentPdfRenderer renderer = new DocumentPdfRenderer();

    private static final Instant NOW = Instant.parse("2026-10-10T21:15:00Z");

    @Test
    void printsTheIdentityReferencesSupplierAndSignOff() throws IOException {
        String text = render(sample("CONFIRMED", "DN-0004", "0.00", "72000.00"));

        assertThat(text).contains("Mwondoko Traders Ltd");
        assertThat(text).contains(DocumentModelBuilder.PURCHASE_RETURN_TITLE);
        assertThat(text).contains("PRET-0012");
        assertThat(text).contains("02-Oct-2026");
        assertThat(text).contains("GRN-0007");
        assertThat(text).contains("PO-0003");
        assertThat(text).contains("DN-0004");
        assertThat(text).contains("Damaged in transit");

        assertThat(text).contains("KONDIKI MILK PROCESSING INDUSTRY");
        assertThat(text).contains("TIN: 109-876-543");
        assertThat(text).contains("VRN: 40-012345-K");

        assertThat(text).contains("Prepared By: RICHARD");
        assertThat(text).contains("Received by Supplier");
        // Printed in the company's zone: 21:15 UTC is 00:15 on the 11th in Dar es Salaam.
        assertThat(text).contains("Printed On: 11-Oct-2026");
        assertThat(text).doesNotContain("DRAFT");
    }

    @Test
    void linesPrintInTheirOwnUnitWithTheStoredValues() throws IOException {
        String text = render(sample("CONFIRMED", "DN-0004", "0.00", "72000.00"));

        assertThat(text).contains("BEER-01");
        assertThat(text).contains("Lager 500ml");
        assertThat(text).contains("CRATE");
        assertThat(text).contains("36,000.00");
        assertThat(text).contains("72,000.00");
        assertThat(text).contains("Net Amount");
        assertThat(text).contains("Total Amount");
        // No VAT on the return: no "VAT Amount 0.00" row.
        assertThat(text).doesNotContain("VAT Amount");
    }

    @Test
    void vatRowAppearsOnlyWhenTheReturnCarriesVat() {
        DocumentRenderModel model = builder.buildPurchaseReturn(
                sample("CONFIRMED", "DN-0004", "12960.00", "84960.00"), branding(), "skarume", NOW);

        assertThat(model.totals()).extracting(DocumentRenderModel.TotalRow::label)
                .containsExactly("Net Amount", "VAT Amount", "Total Amount");
    }

    @Test
    void draftIsStampedAndHasNoDebitNoteLine() throws IOException {
        String text = render(sample("DRAFT", null, "0.00", "72000.00"));

        assertThat(text).contains("DRAFT - NOT CONFIRMED");
        assertThat(text).doesNotContain("Debit Note No.");
    }

    // -------------------------------------------------------------------------

    private String render(PurchaseReturnPrintDto pr) throws IOException {
        return extract(renderer.render(builder.buildPurchaseReturn(pr, branding(), "skarume", NOW)));
    }

    private static DocumentBranding branding() {
        DocumentBranding b = new DocumentBranding(1L, "Mwondoko Traders Ltd");
        b.setTaxId("100-200-300");
        return b;
    }

    private static PurchaseReturnPrintDto sample(String status, String debitNote, String vat,
                                                 String total) {
        return new PurchaseReturnPrintDto(
                "01J00000000000000000000PRT", 1L, "PRET-0012", status,
                "02-Oct-2026", Instant.parse("2026-10-02T07:00:00Z"),
                "GRN-0007", "PO-0003", "MWONDOKO",
                "KONDIKI MILK PROCESSING INDUSTRY", "109-876-543", "40-012345-K",
                List.of("P.O. Box 1234", "Moshi, Kilimanjaro"),
                "Damaged in transit", "TZS", debitNote, "RICHARD",
                List.of(new PurchaseReturnPrintLineDto(1, "BEER-01", "Lager 500ml", "CRATE",
                        new BigDecimal("2"), new BigDecimal("36000.0000"),
                        new BigDecimal("72000.0000"))),
                new BigDecimal("72000.00"), new BigDecimal(vat), new BigDecimal(total),
                "Africa/Dar_es_Salaam");
    }

    private static String extract(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                sb.append(extractor.getTextFromPage(page)).append('\n');
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }
}
