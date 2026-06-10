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
import com.erp.modules.reporting.export.StatementRenderModel.Row;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * PDF renderer using OpenPDF (LGPL) — faithful A4 statement layout (ADR-0018 D-9).
 * Two amount columns (current / comparative), section headers bold, subtotals/totals ruled,
 * reconciliation bar green (ties) / red (does not balance).
 */
@Component
public class PdfStatementRenderer {

    private static final Font FONT_HEADER  = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
    private static final Font FONT_TITLE   = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font FONT_SECTION = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
    private static final Font FONT_NORMAL  = FontFactory.getFont(FontFactory.HELVETICA, 9);
    private static final Font FONT_TOTAL   = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
    private static final Font FONT_RECON_OK  = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new Color(0, 128, 0));
    private static final Font FONT_RECON_BAD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.RED);

    public byte[] render(StatementRenderModel model) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 40, 40);
        PdfWriter.getInstance(doc, baos);
        doc.open();

        // Header block
        doc.add(new Paragraph(model.title(), FONT_HEADER));
        doc.add(new Paragraph(model.companyName() != null ? model.companyName() : "", FONT_TITLE));
        doc.add(new Paragraph(model.periodLabel() != null ? model.periodLabel() : "", FONT_NORMAL));
        doc.add(new Paragraph("Generated: " + model.generatedAt(), FONT_NORMAL));
        doc.add(new Paragraph(" "));

        // Statement table
        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{60f, 20f, 20f});

        // Column headings
        addCell(table, "Description", FONT_TOTAL, Element.ALIGN_LEFT, false, false, null);
        addCell(table, model.periodLabel() != null ? model.periodLabel() : "Current",
                FONT_TOTAL, Element.ALIGN_RIGHT, false, false, null);
        addCell(table, model.comparativeLabel() != null ? model.comparativeLabel() : "Comparative",
                FONT_TOTAL, Element.ALIGN_RIGHT, false, false, null);

        for (Row row : model.rows()) {
            switch (row.rowType()) {
                case SECTION_HEADER -> {
                    addCell(table, row.label(), FONT_SECTION, Element.ALIGN_LEFT, false, false, new Color(230, 230, 230));
                    addCell(table, "", FONT_SECTION, Element.ALIGN_RIGHT, false, false, new Color(230, 230, 230));
                    addCell(table, "", FONT_SECTION, Element.ALIGN_RIGHT, false, false, new Color(230, 230, 230));
                }
                case LINE -> {
                    addCell(table, "  " + row.label(), FONT_NORMAL, Element.ALIGN_LEFT, false, false, null);
                    addCell(table, fmt(row.current()), FONT_NORMAL, Element.ALIGN_RIGHT, false, false, null);
                    addCell(table, fmt(row.comparative()), FONT_NORMAL, Element.ALIGN_RIGHT, false, false, null);
                }
                case SUBTOTAL -> {
                    addCell(table, row.label(), FONT_TOTAL, Element.ALIGN_LEFT, true, false, null);
                    addCell(table, fmt(row.current()), FONT_TOTAL, Element.ALIGN_RIGHT, true, false, null);
                    addCell(table, fmt(row.comparative()), FONT_TOTAL, Element.ALIGN_RIGHT, true, false, null);
                }
                case TOTAL -> {
                    addCell(table, row.label(), FONT_TOTAL, Element.ALIGN_LEFT, true, true, null);
                    addCell(table, fmt(row.current()), FONT_TOTAL, Element.ALIGN_RIGHT, true, true, null);
                    addCell(table, fmt(row.comparative()), FONT_TOTAL, Element.ALIGN_RIGHT, true, true, null);
                }
                case RECONCILIATION -> {
                    Font reconFont = row.ties() ? FONT_RECON_OK : FONT_RECON_BAD;
                    String icon = row.ties() ? "[OK] " : "[!] ";
                    Color bg = row.ties() ? new Color(220, 255, 220) : new Color(255, 220, 220);
                    addCell(table, icon + row.label(), reconFont, Element.ALIGN_LEFT, false, false, bg);
                    addCell(table, fmt(row.current()), reconFont, Element.ALIGN_RIGHT, false, false, bg);
                    addCell(table, "", reconFont, Element.ALIGN_RIGHT, false, false, bg);
                }
            }
        }

        doc.add(table);
        doc.close();
        return baos.toByteArray();
    }

    // -------------------------------------------------------------------------

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

    private String fmt(BigDecimal amt) {
        if (amt == null) return "";
        return String.format("%,.2f", amt);
    }
}
