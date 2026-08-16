package com.erp.modules.documents.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.documents.domain.entity.DocumentBranding;
import com.erp.modules.documents.service.DocumentModelBuilder;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.purchases.domain.dto.GoodsReceiptPrintDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptPrintLineDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptVatBandDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the printed vendor Goods Received Note against the layout the client signed off (K9,
 * 2026-08-12) — the fields they listed, on one page, with the figures in the right columns.
 *
 * <p>The fixture is their own sample note (GRN09985, Kondiki Milk Processing Industry into
 * Mwondoko), so a regression here is visible as "the note stopped matching the sample" rather than
 * as an abstract assertion failure.
 *
 * <p>Asserts on the EXTRACTED TEXT of the real PDF, not on the render model: the model can be
 * perfectly populated while the renderer drops a column, and dropping a column is exactly the
 * failure this document has had before.
 */
class GoodsReceiptNoteLayoutTest {

    // The repository is only consulted when the branding row's TIN is blank, and this fixture's is
    // not — a mock that is never called keeps the layout assertions about the layout.
    private final DocumentModelBuilder builder =
            new DocumentModelBuilder(new ObjectMapper(), org.mockito.Mockito.mock(CompanyRepository.class));
    private final DocumentPdfRenderer  renderer = new DocumentPdfRenderer();

    // -------------------------------------------------------------------------

    @Test
    void printsEveryFieldTheClientAskedFor() throws IOException {
        String text = renderSample();

        // Company + branch + supplier identification
        assertThat(text).contains("Mwondoko Traders Ltd");
        assertThat(text).contains("MWONDOKO");
        assertThat(text).contains("KONDIKI MILK PROCESSING INDUSTRY");
        assertThat(text).contains("TIN: 109-876-543");

        // Document identification
        assertThat(text).contains("Goods Received Note (Vendor)");
        assertThat(text).contains("GRN09985");
        assertThat(text).contains("G.R.N. No.");
        assertThat(text).contains("GRN Date");
        assertThat(text).contains("TZS");
    }

    @Test
    void printsTheLineColumnsInTheOrderTheNoteUses() throws IOException {
        String text = renderSample();

        assertThat(text).contains("Code");
        assertThat(text).contains("Product Description");
        assertThat(text).contains("Qty");
        assertThat(text).contains("CP");
        assertThat(text).contains("SP");
        assertThat(text).contains("Last CP");
        assertThat(text).contains("Mar.(%)");
        assertThat(text).contains("Amount");

        // The item code column is the one the GRN gained in K9 — the model has always carried the
        // code, and the renderer used to throw it away.
        assertThat(text).contains("00009945");
        assertThat(text).contains("KONDIKI MTINDI 5LT");

        // Cost, shelf price, previous cost and the margin between the first two, on one row.
        assertThat(text).contains("13,000.00");
        assertThat(text).contains("15,000.00");
        assertThat(text).contains("13.33");
        assertThat(text).contains("260,000.00");
    }

    @Test
    void printsTheNetVatTotalFootAndTheSignOffRow() throws IOException {
        String text = renderSample();

        assertThat(text).contains("Net Amount");
        assertThat(text).contains("Vat Amount");
        assertThat(text).contains("Rounding Amount");
        assertThat(text).contains("Total Amount");
        assertThat(text).contains("885,000.00");

        assertThat(text).contains("Prepared By: RICHARD");
        assertThat(text).contains("Checked By");
        assertThat(text).contains("Auth. By");
        assertThat(text).contains("Accounts");
        assertThat(text).contains("Security");

        // Print footprint + page count: a reprinted GRN must say who printed this copy and whether
        // a sheet is missing.
        assertThat(text).contains("Printed By: skarume");
        assertThat(text).contains("Printed From: MWONDOKO");
        assertThat(text).contains("Page 1 of 1");
    }

    /**
     * A VOID receipt still prints — reprints are legitimate — but it must say so on its face, or a
     * reversed delivery passes for a live one at the counter.
     */
    @Test
    void stampsAVoidedReceipt() throws IOException {
        GoodsReceiptPrintDto voided = withStatus(sample(), "VOID");
        String text = extract(renderer.render(
                builder.buildGoodsReceipt(voided, branding(), "Goods Received Note (Vendor)", "skarume")));

        assertThat(text).contains("VOID");
    }

    /**
     * A product nobody has priced, and a first-ever receipt of it, leave SP / Last CP / Mar(%) blank
     * rather than printing 0.00 — a zero in a margin column is a claim about the shop's margin that
     * nothing supports, and a zero previous cost reads as "it used to be free".
     */
    @Test
    void leavesUnknownComparisonFiguresBlankRatherThanZero() throws IOException {
        GoodsReceiptPrintDto gr = new GoodsReceiptPrintDto(
                "01J000000000000000000000GR", 1L, "GRN00001", "RECEIVED",
                Instant.parse("2026-08-06T09:00:00Z"), "PO-0001",
                "NEW SUPPLIER LTD", null, List.of(), "MWONDOKO", "TZS", null, "RICHARD",
                List.of(new GoodsReceiptPrintLineDto(
                        1, "00000001", "BRAND NEW ITEM", new BigDecimal("5"), "PCS",
                        new BigDecimal("1000.0000"), null, null, null,
                        new BigDecimal("5000.0000"), "EXEMPT")),
                List.of(new GoodsReceiptVatBandDto("EXEMPT", BigDecimal.ZERO,
                        new BigDecimal("5000.00"), BigDecimal.ZERO)),
                new BigDecimal("5000.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("5000.00"));

        String text = extract(renderer.render(
                builder.buildGoodsReceipt(gr, branding(), "Goods Received Note (Vendor)", "skarume")));

        assertThat(text).contains("BRAND NEW ITEM");
        assertThat(text).contains("1,000.00");
        // Nothing populates the comparison columns, so the renderer drops them entirely rather than
        // printing three empty columns that read as a rendering fault.
        assertThat(text).doesNotContain("Mar.(%)");
        assertThat(text).doesNotContain("Last CP");
    }

    /**
     * The other document types share this renderer. None of them asked for an item-code column, a
     * sign-off row or a print footprint, and none of them should have grown one.
     */
    @Test
    void leavesOtherDocumentTypesAlone() throws IOException {
        DocumentRenderModel deliveryNote = new DocumentRenderModel(
                "DELIVERY NOTE", new DocumentRenderModel.BrandingBlock(
                        "Mwondoko Traders Ltd", null, null, List.of(), null, null, null, null, null),
                List.of(new DocumentRenderModel.MetaPair("Delivery No.", "DN-0001")),
                new DocumentRenderModel.PartyBlock("A CUSTOMER", List.of(), null),
                List.of(new DocumentRenderModel.DocLine(
                        1, "00009945", "KONDIKI MTINDI 5LT", new BigDecimal("20"), "PCS",
                        null, null, null, null)),
                List.of(), List.of(), null, Instant.now().toString(), null,
                DocumentRenderModel.Layout.plain());

        String text = extract(renderer.render(deliveryNote));

        assertThat(text).contains("KONDIKI MTINDI 5LT");
        assertThat(text).doesNotContain("Code");
        assertThat(text).doesNotContain("00009945");
        assertThat(text).doesNotContain("Checked By");
        assertThat(text).doesNotContain("Printed By");
    }

    // -------------------------------------------------------------------------
    // Fixture — the client's own sample note
    // -------------------------------------------------------------------------

    private String renderSample() throws IOException {
        return extract(renderer.render(
                builder.buildGoodsReceipt(sample(), branding(),
                        "Goods Received Note (Vendor)", "skarume")));
    }

    private static DocumentBranding branding() {
        DocumentBranding b = new DocumentBranding(1L, "Mwondoko Traders Ltd");
        b.setTaxId("100-200-300");
        return b;
    }

    private static GoodsReceiptPrintDto sample() {
        List<GoodsReceiptPrintLineDto> lines = List.of(
                line(1, "00009945", "KONDIKI MTINDI 5LT",    20, "13000", "15000", "13000", "13.33", "260000"),
                line(2, "00009020", "KONDIKI MTINDI 3LTS",   30,  "8500", "10500",  "8500", "19.05", "255000"),
                line(3, "00006693", "KONDIKI MTINDI 1LT",    50,  "2700",  "3200",  "2700", "15.62", "135000"),
                line(4, "00006367", "KONDIKI MTINDI 500GM",  50,  "1500",  "2000",  "1500", "25.00",  "75000"),
                line(5, "00012780", "KONDIKI STRAWBERRY YOGHURT 500ML",
                                                             15,  "2000",  "2500",  "2000", "20.00",  "30000"),
                line(6, "00006369", "KONDIKI SIAGI BUTTER 500GM",
                                                             10, "13000", "15000", "13000", "13.33", "130000"));

        return new GoodsReceiptPrintDto(
                "01J000000000000000000000GR", 1L, "GRN09985", "RECEIVED",
                Instant.parse("2026-08-06T09:00:00Z"), "PO-0099",
                "KONDIKI MILK PROCESSING INDUSTRY", "109-876-543",
                List.of("P.O. Box 1234", "Moshi, Kilimanjaro", "TZ"),
                "MWONDOKO", "TZS", null, "RICHARD",
                lines,
                List.of(new GoodsReceiptVatBandDto("EXEMPT", BigDecimal.ZERO,
                        new BigDecimal("885000.00"), BigDecimal.ZERO)),
                new BigDecimal("885000.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("885000.00"));
    }

    private static GoodsReceiptPrintLineDto line(int no, String code, String name, int qty,
                                                  String cp, String sp, String lastCp,
                                                  String margin, String amount) {
        return new GoodsReceiptPrintLineDto(
                no, code, name, new BigDecimal(qty), "PCS",
                new BigDecimal(cp), new BigDecimal(sp), new BigDecimal(lastCp),
                new BigDecimal(margin), new BigDecimal(amount), "EXEMPT");
    }

    private static GoodsReceiptPrintDto withStatus(GoodsReceiptPrintDto gr, String status) {
        return new GoodsReceiptPrintDto(
                gr.uid(), gr.companyId(), gr.receiptNumber(), status, gr.receivedAt(),
                gr.purchaseOrderNumber(), gr.supplierName(), gr.supplierTin(),
                gr.supplierAddressLines(), gr.branchName(), gr.currency(), gr.notes(),
                gr.preparedByName(), gr.lines(), gr.vatBands(),
                gr.netAmount(), gr.vatAmount(), gr.roundingAmount(), gr.totalAmount());
    }

    /** All pages of the rendered PDF as one string. */
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
