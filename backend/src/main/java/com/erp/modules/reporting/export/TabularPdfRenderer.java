package com.erp.modules.reporting.export;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * PDF renderer for {@link TabularRenderModel} using OpenPDF (LGPL) — A4, N columns. Sibling of
 * {@link PdfStatementRenderer} (the fixed 3-column financial shape). Handles long reports (thousands
 * of rows) — OpenPDF streams the table; no in-memory row limit is imposed here.
 */
@Component
public class TabularPdfRenderer {

    private static final Font FONT_TITLE  = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
    private static final Font FONT_HEADER = FontFactory.getFont(FontFactory.HELVETICA, 9);
    private static final Font FONT_COLHEAD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
    private static final Font FONT_NORMAL = FontFactory.getFont(FontFactory.HELVETICA, 9);
    private static final Font FONT_TOTAL  = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);

    public byte[] render(TabularRenderModel model) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 40, 40);
        PdfWriter.getInstance(doc, baos);
        doc.open();

        embedLogo(doc, model.logoDataUri());
        doc.add(new Paragraph(model.title(), FONT_TITLE));
        if (model.headerLines() != null) {
            for (String line : model.headerLines()) {
                doc.add(new Paragraph(line != null ? line : "", FONT_HEADER));
            }
        }
        doc.add(new Paragraph("Generated: " + model.generatedAt(), FONT_HEADER));
        doc.add(new Paragraph(" "));

        int columnCount = model.columns().size();
        PdfPTable table = new PdfPTable(columnCount);
        table.setWidthPercentage(100);
        table.setWidths(columnWidths(model.columns()));

        for (TabularRenderModel.Column col : model.columns()) {
            addCell(table, col.header(), FONT_COLHEAD, toPdfAlign(col.align()), true, new Color(230, 230, 230));
        }

        for (List<String> row : model.rows()) {
            for (int c = 0; c < columnCount; c++) {
                String cell = c < row.size() ? row.get(c) : "";
                addCell(table, cell, FONT_NORMAL, toPdfAlign(model.columns().get(c).align()), false, null);
            }
        }

        if (model.totalsRow() != null) {
            for (int c = 0; c < columnCount; c++) {
                String cell = c < model.totalsRow().size() ? model.totalsRow().get(c) : "";
                addCell(table, cell, FONT_TOTAL, toPdfAlign(model.columns().get(c).align()), true, null);
            }
        }

        doc.add(table);

        // Foot of the document: the print footprint, and any sign-off rules. Below the table, which
        // is where a reader looks for "who produced this and when" — the header already carries what
        // the document IS.
        if (!model.footerLines().isEmpty()) {
            doc.add(new Paragraph(" "));
            for (String line : model.footerLines()) {
                doc.add(new Paragraph(line != null ? line : "", FONT_HEADER));
            }
        }

        doc.close();
        return baos.toByteArray();
    }

    /**
     * Draw the company logo above the title, mirroring the documents module's own header so a
     * transfer and a goods-received note from the same company look like they came from it.
     *
     * <p>Best-effort by design: a missing, malformed or unsupported logo leaves a text-only
     * document rather than failing the export. Nobody should be unable to print a transfer note
     * because somebody uploaded a corrupt image.
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
            byte[] bytes = java.util.Base64.getDecoder().decode(dataUri.substring(comma + 1).trim());
            com.lowagie.text.Image logo = com.lowagie.text.Image.getInstance(bytes);
            logo.scaleToFit(57f, 57f);   // ~2 cm at 72 dpi, same as the document renderer
            doc.add(logo);
        } catch (Exception ignored) {
            // Unsupported or corrupt bytes — text-only header.
        }
    }

    // -------------------------------------------------------------------------

    /** LEFT columns (typically code/description text) get twice the relative width of RIGHT/CENTER. */
    private float[] columnWidths(List<TabularRenderModel.Column> columns) {
        float[] widths = new float[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            widths[i] = columns.get(i).align() == TabularRenderModel.Align.LEFT ? 2f : 1f;
        }
        return widths;
    }

    private int toPdfAlign(TabularRenderModel.Align align) {
        return switch (align) {
            case LEFT   -> Element.ALIGN_LEFT;
            case RIGHT  -> Element.ALIGN_RIGHT;
            case CENTER -> Element.ALIGN_CENTER;
        };
    }

    private void addCell(PdfPTable table, String text, Font font, int align,
                          boolean topBorder, Color bg) {
        PdfPCell cell = new PdfPCell(new Phrase(text != null ? text : "", font));
        cell.setHorizontalAlignment(align);
        cell.setPaddingBottom(3);
        cell.setPaddingTop(3);
        cell.setBorderWidth(0);
        if (topBorder) cell.setBorderWidthTop(0.5f);
        if (bg != null) cell.setBackgroundColor(bg);
        table.addCell(cell);
    }
}
