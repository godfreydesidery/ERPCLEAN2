package com.erp.modules.documents.render;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.erp.modules.documents.render.DocumentRenderModel.BrandingBlock;
import com.erp.modules.documents.render.DocumentRenderModel.DocLine;
import com.erp.modules.documents.render.DocumentRenderModel.TaxRow;
import com.erp.modules.documents.render.DocumentRenderModel.TotalRow;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
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
    private static final Font FONT_VOID     = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 36, Color.RED);
    private static final Color COL_HEADER_BG = new Color(52, 73, 94);
    private static final Color COL_HEADER_FG = Color.WHITE;
    private static final Font FONT_COL_HEAD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, COL_HEADER_FG);

    public byte[] render(DocumentRenderModel model) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 40, 40);
        PdfWriter.getInstance(doc, baos);
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
        renderLineTable(doc, model.lines(), hasPrices);
        doc.add(new Paragraph(" ", FONT_NORMAL));

        // Tax summary + totals (omitted for qty-only docs)
        if (!model.taxSummary().isEmpty()) {
            renderTaxSummary(doc, model.taxSummary());
        }
        if (!model.totals().isEmpty()) {
            renderTotals(doc, model.totals());
        }

        // Footer
        if (model.branding().footerTerms() != null) {
            doc.add(new Paragraph(" ", FONT_NORMAL));
            doc.add(new Paragraph(model.branding().footerTerms(), FONT_SMALL));
        }
        if (model.branding().bankDetails() != null) {
            doc.add(new Paragraph(model.branding().bankDetails(), FONT_SMALL));
        }

        doc.add(new Paragraph("Generated: " + model.generatedAt(), FONT_SMALL));

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

    private void renderLineTable(Document doc, List<DocLine> lines, boolean hasPrices) {
        int cols = hasPrices ? 7 : 4; // code, description, qty, unit [, unitPrice, discount, lineTotal]
        PdfPTable table = new PdfPTable(cols);
        table.setWidthPercentage(100);

        if (hasPrices) {
            try { table.setWidths(new float[]{8f, 30f, 10f, 8f, 14f, 10f, 14f}); } catch (Exception ignored) {}
        } else {
            try { table.setWidths(new float[]{8f, 50f, 22f, 20f}); } catch (Exception ignored) {}
        }

        // Header row
        addHeaderCell(table, "#");
        addHeaderCell(table, "Description");
        addHeaderCell(table, "Qty");
        addHeaderCell(table, "Unit");
        if (hasPrices) {
            addHeaderCell(table, "Unit Price");
            addHeaderCell(table, "Discount");
            addHeaderCell(table, "Total");
        }

        for (DocLine l : lines) {
            addCell(table, String.valueOf(l.lineNo()), FONT_NORMAL, Element.ALIGN_RIGHT, false, false, null);
            addCell(table, l.description() != null ? l.description() : "", FONT_NORMAL, Element.ALIGN_LEFT, false, false, null);
            addCell(table, fmt(l.qty()), FONT_NORMAL, Element.ALIGN_RIGHT, false, false, null);
            addCell(table, l.unit() != null ? l.unit() : "", FONT_NORMAL, Element.ALIGN_LEFT, false, false, null);
            if (hasPrices) {
                addCell(table, fmtAmt(l.unitPrice()), FONT_NORMAL, Element.ALIGN_RIGHT, false, false, null);
                addCell(table, fmtAmt(l.discount()), FONT_NORMAL, Element.ALIGN_RIGHT, false, false, null);
                addCell(table, fmtAmt(l.lineTotal()), FONT_NORMAL, Element.ALIGN_RIGHT, false, false, null);
            }
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

    private String fmt(BigDecimal val) {
        if (val == null) return "";
        return String.format("%,.4f", val.stripTrailingZeros().toPlainString().contains(".")
                ? val : val);
    }

    private String fmtAmt(BigDecimal val) {
        if (val == null) return "";
        return String.format("%,.2f", val);
    }
}
