package com.erp.modules.documents.render;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import com.erp.modules.documents.render.DocumentRenderModel.BrandingBlock;
import com.erp.modules.documents.render.DocumentRenderModel.DocLine;
import com.erp.modules.documents.render.DocumentRenderModel.Layout;
import com.erp.modules.documents.render.DocumentRenderModel.TaxRow;
import com.erp.modules.documents.render.DocumentRenderModel.TotalRow;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * PDF renderer for transactional documents (ADR-0023 D-5).
 * Renders a {@link DocumentRenderModel} using OpenPDF (LGPL — NFR-DOC-09).
 * Reuses the same OpenPDF primitives (addCell / font / PdfPTable) as PdfStatementRenderer —
 * one pipeline, two layouts (NFR-DOC-01).
 */
@Component
public class DocumentPdfRenderer {

    private static final Font FONT_COMPANY  = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
    private static final Font FONT_HEADER   = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font FONT_TITLE    = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
    private static final Font FONT_NORMAL   = FontFactory.getFont(FontFactory.HELVETICA, 9);
    private static final Font FONT_BOLD     = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
    private static final Font FONT_SMALL    = FontFactory.getFont(FontFactory.HELVETICA, 8);
    /** Body font for wide tables (the 9-column GRN); anything larger wraps every description. */
    private static final Font FONT_TINY     = FontFactory.getFont(FontFactory.HELVETICA, 7.5f);
    private static final Font FONT_VOID     = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 36, Color.RED);
    private static final Color COL_HEADER_BG = new Color(52, 73, 94);
    private static final Color COL_HEADER_FG = Color.WHITE;
    private static final Font FONT_COL_HEAD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, COL_HEADER_FG);

    public byte[] render(DocumentRenderModel model) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 40, 40);
        PdfWriter writer = PdfWriter.getInstance(doc, baos);
        // "Page x of y" on every page. A GRN circulates as paper and a delivery of 60 lines runs to
        // several sheets; without the count, a missing middle page is invisible to whoever signs it.
        writer.setPageEvent(new PageFooter());
        doc.open();

        // Branding header
        renderBranding(doc, model.branding());

        // Document title
        Paragraph title = new Paragraph(model.title(), FONT_TITLE);
        title.setAlignment(Element.ALIGN_RIGHT);
        doc.add(title);
        doc.add(new Paragraph(" ", FONT_NORMAL));

        // VOID watermark
        if (model.voidLabel() != null) {
            Paragraph voidPara = new Paragraph(model.voidLabel(), FONT_VOID);
            voidPara.setAlignment(Element.ALIGN_CENTER);
            doc.add(voidPara);
        }

        // Meta + counterparty side-by-side
        renderMetaAndParty(doc, model);
        doc.add(new Paragraph(" ", FONT_NORMAL));

        // Line table
        boolean hasPrices = model.lines().stream()
                .anyMatch(l -> l.unitPrice() != null || l.lineTotal() != null);
        renderLineTable(doc, model.lines(), hasPrices, model.layout());
        doc.add(new Paragraph(" ", FONT_NORMAL));

        // Tax summary + totals (omitted for qty-only docs)
        if (!model.taxSummary().isEmpty()) {
            renderTaxSummary(doc, model.taxSummary());
        }
        if (!model.totals().isEmpty()) {
            renderTotals(doc, model.totals());
        }

        // Sign-off row (K9) — printed before the terms so the signatures sit with the figures they
        // authorise rather than under a block of boilerplate.
        if (!model.layout().signatories().isEmpty()) {
            renderSignatories(doc, model.layout().signatories());
        }

        // Footer
        if (model.branding().footerTerms() != null) {
            doc.add(new Paragraph(" ", FONT_NORMAL));
            doc.add(new Paragraph(model.branding().footerTerms(), FONT_SMALL));
        }
        if (model.branding().bankDetails() != null) {
            doc.add(new Paragraph(model.branding().bankDetails(), FONT_SMALL));
        }

        if (model.layout().printFooter() != null) {
            doc.add(new Paragraph(model.layout().printFooter(), FONT_SMALL));
        } else {
            doc.add(new Paragraph("Generated: " + model.generatedAt(), FONT_SMALL));
        }

        doc.close();
        return baos.toByteArray();
    }

    // -------------------------------------------------------------------------

    private void renderBranding(Document doc, BrandingBlock b) {
        embedLogo(doc, b.logoDataUri());
        doc.add(new Paragraph(b.displayName() != null ? b.displayName() : "", FONT_COMPANY));
        if (b.legalName() != null) {
            doc.add(new Paragraph(b.legalName(), FONT_NORMAL));
        }
        if (b.taxId() != null) {
            doc.add(new Paragraph("TIN/VAT: " + b.taxId(), FONT_NORMAL));
        }
        if (b.addressLines() != null) {
            for (String line : b.addressLines()) {
                doc.add(new Paragraph(line, FONT_NORMAL));
            }
        }
        if (b.contactLine() != null) {
            doc.add(new Paragraph(b.contactLine(), FONT_NORMAL));
        }
        doc.add(new Paragraph(" ", FONT_NORMAL));
    }

    /**
     * Embed the company logo (a base64 PNG/JPEG data URI) into the header, scaled to ~2x2 cm.
     * Best-effort: any decode/format problem leaves a text-only header rather than failing the render.
     */
    private void embedLogo(Document doc, String dataUri) {
        if (dataUri == null || dataUri.isBlank()) {
            return;
        }
        int comma = dataUri.indexOf(',');
        if (comma < 0) {
            return;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(dataUri.substring(comma + 1).trim());
            Image logo = Image.getInstance(bytes);
            logo.scaleToFit(57f, 57f); // ~2 cm at 72 dpi
            doc.add(logo);
        } catch (Exception ignored) {
            // Unsupported / corrupt logo bytes — fall back to the text-only header.
        }
    }

    private void renderMetaAndParty(Document doc, DocumentRenderModel model) {
        PdfPTable outer = new PdfPTable(2);
        outer.setWidthPercentage(100);

        // Left: meta pairs
        PdfPTable metaTable = new PdfPTable(2);
        if (model.meta() != null) {
            for (var mp : model.meta()) {
                addSimpleCell(metaTable, mp.label() + ":", FONT_BOLD, Element.ALIGN_LEFT);
                addSimpleCell(metaTable, mp.value() != null ? mp.value() : "", FONT_NORMAL, Element.ALIGN_LEFT);
            }
        }
        PdfPCell metaCell = new PdfPCell(metaTable);
        metaCell.setBorderWidth(0);
        outer.addCell(metaCell);

        // Right: counterparty
        PdfPTable partyTable = new PdfPTable(1);
        var cp = model.counterparty();
        if (cp != null) {
            addSimpleCell(partyTable, cp.name() != null ? cp.name() : "", FONT_BOLD, Element.ALIGN_LEFT);
            if (cp.addressLines() != null) {
                for (String al : cp.addressLines()) {
                    addSimpleCell(partyTable, al, FONT_NORMAL, Element.ALIGN_LEFT);
                }
            }
            if (cp.taxId() != null) {
                addSimpleCell(partyTable, "TIN: " + cp.taxId(), FONT_NORMAL, Element.ALIGN_LEFT);
            }
        }
        PdfPCell partyCell = new PdfPCell(partyTable);
        partyCell.setBorderWidth(0);
        outer.addCell(partyCell);

        doc.add(outer);
    }

    /**
     * Renders the line table. The column set is assembled from what the lines actually carry, plus
     * the one switch the document type opts into:
     *
     * <ul>
     *   <li><b>Item code</b> — {@code layout.showItemCode}. Opt-in, not inferred: every builder
     *       populates {@code DocLine.code}, so inferring it would have silently added a column to the
     *       invoice, the proforma and the delivery note the day the GRN asked for one (K9). When the
     *       code column is shown the line-number column is dropped — two identifiers side by side on
     *       a document whose reader is looking things up by code is noise.</li>
     *   <li><b>Prices</b> — Unit Price / Total, whenever any line carries either.</li>
     *   <li><b>Discount</b> — only when some line carries one. A document type with no discount
     *       concept at all — a GRN — would otherwise print a permanently blank column between two
     *       figures, which reads as a rendering fault rather than as "no discount" (K2). An invoice
     *       line with a zero discount still carries a non-null 0.00 and so keeps its column.</li>
     *   <li><b>Selling price / Last CP / Mar(%)</b> — the buying-side trio, shown only when some line
     *       carries them, i.e. on the GRN (K9).</li>
     * </ul>
     */
    private void renderLineTable(Document doc, List<DocLine> lines, boolean hasPrices, Layout layout) {
        ColumnPlan plan = planColumns(lines, hasPrices, layout.showItemCode());

        PdfPTable table = new PdfPTable(plan.headers().size());
        table.setWidthPercentage(100);
        try {
            table.setWidths(plan.widths());
        } catch (Exception ignored) {
            // Width hint rejected (never seen with a matching count) — even columns still print.
        }
        // The header row repeats when a long receipt spills onto a second sheet; a continuation page
        // of bare figures under no headings is unreadable.
        table.setHeaderRows(1);

        for (String h : plan.headers()) {
            addHeaderCell(table, h);
        }

        // A nine-column GRN at 9 pt wraps every description onto two lines on A4 portrait. Step the
        // body down a point and a half once the table gets wide; the header stays put.
        Font body = plan.headers().size() > 7 ? FONT_TINY : FONT_NORMAL;
        for (DocLine l : lines) {
            addLineRow(table, l, plan, body);
        }
        doc.add(table);
    }

    /** Which columns this document's lines earn, and how wide each one is. */
    private record ColumnPlan(List<String> headers, float[] widths, boolean showCode,
                               boolean prices, boolean discount, boolean selling,
                               boolean lastCost, boolean margin) {}

    private static ColumnPlan planColumns(List<DocLine> lines, boolean hasPrices, boolean showCode) {
        boolean discount = hasPrices && lines.stream().anyMatch(l -> l.discount() != null);
        boolean selling  = lines.stream().anyMatch(l -> l.sellingPrice() != null);
        boolean lastCost = lines.stream().anyMatch(l -> l.lastCost() != null);
        boolean margin   = lines.stream().anyMatch(l -> l.marginPercent() != null);

        List<String> headers = new ArrayList<>();
        List<Float>  widths  = new ArrayList<>();
        if (showCode) {
            headers.add("Code");             widths.add(11f);
        } else {
            headers.add("#");                widths.add(6f);
        }
        headers.add("Product Description");  widths.add(showCode ? 25f : 34f);
        headers.add("Qty");                  widths.add(7f);
        headers.add("Unit");                 widths.add(7f);
        if (hasPrices) {
            // "CP" only where an "SP" sits beside it to contrast with; on an invoice or a PO the
            // column is just the price and "Unit Price" says so without a glossary.
            headers.add(selling ? "CP" : "Unit Price"); widths.add(11f);
            if (discount) { headers.add("Discount");    widths.add(10f); }
            if (selling)  { headers.add("SP");          widths.add(11f); }
            if (lastCost) { headers.add("Last CP");     widths.add(11f); }
            if (margin)   { headers.add("Mar.(%)");     widths.add(8f);  }
            headers.add("Amount");                      widths.add(12f);
        }

        float[] w = new float[widths.size()];
        for (int i = 0; i < widths.size(); i++) {
            w[i] = widths.get(i);
        }
        return new ColumnPlan(List.copyOf(headers), w, showCode,
                hasPrices, discount, selling, lastCost, margin);
    }

    private void addLineRow(PdfPTable table, DocLine l, ColumnPlan plan, Font body) {
        if (plan.showCode()) {
            addCell(table, l.code() != null ? l.code() : "", body, Element.ALIGN_LEFT, false, false, null);
        } else {
            addCell(table, String.valueOf(l.lineNo()), body, Element.ALIGN_RIGHT, false, false, null);
        }
        addCell(table, l.description() != null ? l.description() : "", body, Element.ALIGN_LEFT, false, false, null);
        addCell(table, fmt(l.qty()), body, Element.ALIGN_RIGHT, false, false, null);
        addCell(table, l.unit() != null ? l.unit() : "", body, Element.ALIGN_LEFT, false, false, null);
        if (!plan.prices()) {
            return;
        }
        addCell(table, fmtAmt(l.unitPrice()), body, Element.ALIGN_RIGHT, false, false, null);
        if (plan.discount()) {
            addCell(table, fmtAmt(l.discount()), body, Element.ALIGN_RIGHT, false, false, null);
        }
        if (plan.selling()) {
            addCell(table, fmtAmt(l.sellingPrice()), body, Element.ALIGN_RIGHT, false, false, null);
        }
        if (plan.lastCost()) {
            addCell(table, fmtAmt(l.lastCost()), body, Element.ALIGN_RIGHT, false, false, null);
        }
        if (plan.margin()) {
            addCell(table, fmtAmt(l.marginPercent()), body, Element.ALIGN_RIGHT, false, false, null);
        }
        addCell(table, fmtAmt(l.lineTotal()), body, Element.ALIGN_RIGHT, false, false, null);
    }

    /**
     * The sign-off row: one ruled blank per signatory, laid out across the page width.
     *
     * <p>The rule is drawn as a cell top border under a spacer row rather than a run of underscores,
     * so the line is straight and full-width whatever the label length — a row of "________" reads as
     * a fax from 1994 and, worse, wanders when a name is printed above it.
     */
    private void renderSignatories(Document doc, List<String> labels) {
        doc.add(new Paragraph(" ", FONT_NORMAL));
        doc.add(new Paragraph(" ", FONT_NORMAL));

        PdfPTable table = new PdfPTable(labels.size());
        table.setWidthPercentage(100);

        // Row 1: the space the signature is written into.
        for (int i = 0; i < labels.size(); i++) {
            PdfPCell space = new PdfPCell(new Phrase(" ", FONT_SMALL));
            space.setBorderWidth(0);
            space.setFixedHeight(26f);
            table.addCell(space);
        }
        // Row 2: the rule, then the label beneath it.
        for (String label : labels) {
            PdfPCell rule = new PdfPCell(new Phrase(label, FONT_SMALL));
            rule.setBorderWidth(0);
            rule.setBorderWidthTop(0.5f);
            rule.setPaddingTop(3);
            rule.setPaddingRight(8);
            table.addCell(rule);
        }
        doc.add(table);
    }

    private void renderTaxSummary(Document doc, List<TaxRow> rows) {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(50);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        addHeaderCell(table, "Tax Band");
        addHeaderCell(table, "Base");
        addHeaderCell(table, "Rate %");
        addHeaderCell(table, "VAT");
        for (TaxRow r : rows) {
            addCell(table, r.bandLabel(), FONT_NORMAL, Element.ALIGN_LEFT, false, false, null);
            addCell(table, fmtAmt(r.base()), FONT_NORMAL, Element.ALIGN_RIGHT, false, false, null);
            addCell(table, fmtAmt(r.rate()), FONT_NORMAL, Element.ALIGN_RIGHT, false, false, null);
            addCell(table, fmtAmt(r.vat()), FONT_NORMAL, Element.ALIGN_RIGHT, false, false, null);
        }
        doc.add(table);
    }

    private void renderTotals(Document doc, List<TotalRow> rows) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(40);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        for (TotalRow r : rows) {
            Font f = r.emphasised() ? FONT_BOLD : FONT_NORMAL;
            addCell(table, r.label(), f, Element.ALIGN_LEFT, r.emphasised(), r.emphasised(), null);
            addCell(table, fmtAmt(r.amount()), f, Element.ALIGN_RIGHT, r.emphasised(), r.emphasised(), null);
        }
        doc.add(table);
    }

    // -------------------------------------------------------------------------

    private void addHeaderCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, FONT_COL_HEAD));
        cell.setBackgroundColor(COL_HEADER_BG);
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        cell.setPaddingBottom(4);
        cell.setPaddingTop(4);
        cell.setBorderWidth(0);
        table.addCell(cell);
    }

    private void addSimpleCell(PdfPTable table, String text, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(align);
        cell.setBorderWidth(0);
        cell.setPaddingBottom(2);
        cell.setPaddingTop(2);
        table.addCell(cell);
    }

    private void addCell(PdfPTable table, String text, Font font, int align,
                          boolean topBorder, boolean bottomBorder, Color bg) {
        PdfPCell cell = new PdfPCell(new Phrase(text != null ? text : "", font));
        cell.setHorizontalAlignment(align);
        cell.setPaddingBottom(3);
        cell.setPaddingTop(3);
        cell.setBorderWidth(0);
        if (topBorder)    cell.setBorderWidthTop(0.5f);
        if (bottomBorder) cell.setBorderWidthBottom(0.5f);
        if (bg != null)   cell.setBackgroundColor(bg);
        table.addCell(cell);
    }

    /**
     * Stamps "Page x of y" at the foot of every page.
     *
     * <p>The total is not known until the document closes, so each page gets a placeholder template
     * whose content is filled in once at {@code onCloseDocument} — the standard OpenPDF/iText idiom.
     * The alternative, a second rendering pass to count pages, doubles the work on every document in
     * the system to number the handful that run past one sheet.
     */
    private static final class PageFooter extends PdfPageEventHelper {

        private PdfTemplate total;

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            total = writer.getDirectContent().createTemplate(50, 12);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle page = document.getPageSize();
            PdfContentByte cb = writer.getDirectContent();
            float y = document.bottomMargin() - 14;

            Phrase prefix = new Phrase("Page " + writer.getPageNumber() + " of", FONT_SMALL);
            float x = page.getWidth() - document.rightMargin() - 60;
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, prefix, x, y, 0);
            cb.addTemplate(total, x + 2, y);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            if (total == null || FONT_SMALL.getBaseFont() == null) {
                return;  // No template or no resolved base font — leave the count blank, not broken.
            }
            // getPageNumber() is one past the last written page at close time.
            total.beginText();
            total.setFontAndSize(FONT_SMALL.getBaseFont(), FONT_SMALL.getSize());
            total.setTextMatrix(0, 0);
            // Leading space, not a trailing one on the prefix: a trailing space is dropped from the
            // content stream, which glues the count onto "of" for anything reading the text back.
            total.showText(" " + (writer.getPageNumber() - 1));
            total.endText();
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Quantity: up to 4 dp, with trailing zeros dropped — 20 units print as "20", 1.5 kg as "1.5".
     * A stock quantity padded to "20.0000" on every line of a six-line note is noise the reader has
     * to look past, and it does not match the printed notes these documents sit alongside.
     */
    private String fmt(BigDecimal val) {
        if (val == null) return "";
        BigDecimal trimmed = val.stripTrailingZeros();
        if (trimmed.scale() <= 0) {
            return String.format("%,d", trimmed.toBigIntegerExact());
        }
        return String.format("%,." + Math.min(trimmed.scale(), 4) + "f", val);
    }

    private String fmtAmt(BigDecimal val) {
        if (val == null) return "";
        return String.format("%,.2f", val);
    }
}
