package com.erp.modules.reporting.export;

import com.erp.modules.reporting.export.StatementRenderModel.Row;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * XLSX renderer using Apache POI (Apache-2.0 — ADR-0018 D-9).
 * Amounts are written as numbers so the spreadsheet is workable.
 * Header frozen at row 5; section headers shaded; subtotals/totals bold.
 */
@Component
public class XlsxStatementRenderer {

    public byte[] render(StatementRenderModel model) {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Statement");
            sheet.setColumnWidth(0, 15000);
            sheet.setColumnWidth(1, 5000);
            sheet.setColumnWidth(2, 5000);

            // Styles
            CellStyle boldStyle   = boldStyle(wb, false, IndexedColors.WHITE);
            CellStyle headerStyle = boldStyle(wb, true, IndexedColors.GREY_25_PERCENT);
            CellStyle normalStyle = normalStyle(wb);
            CellStyle subtotalSt  = boldStyle(wb, false, IndexedColors.WHITE);
            CellStyle reconOkSt   = reconStyle(wb, IndexedColors.LIGHT_GREEN);
            CellStyle reconBadSt  = reconStyle(wb, IndexedColors.ROSE);

            int rowIdx = 0;

            // Document header rows
            writeHeaderRow(sheet, rowIdx++, model.title(), boldStyle);
            writeHeaderRow(sheet, rowIdx++, model.companyName() != null ? model.companyName() : "", normalStyle);
            writeHeaderRow(sheet, rowIdx++, model.periodLabel() != null ? model.periodLabel() : "", normalStyle);
            writeHeaderRow(sheet, rowIdx++, "Generated: " + model.generatedAt(), normalStyle);

            // Column headings
            org.apache.poi.ss.usermodel.Row colHead = sheet.createRow(rowIdx++);
            colHead.createCell(0).setCellValue("Description");
            colHead.getCell(0).setCellStyle(boldStyle);
            Cell curHead = colHead.createCell(1);
            curHead.setCellValue(model.periodLabel() != null ? model.periodLabel() : "Current");
            curHead.setCellStyle(boldStyle);
            Cell cmpHead = colHead.createCell(2);
            cmpHead.setCellValue(model.comparativeLabel() != null ? model.comparativeLabel() : "Comparative");
            cmpHead.setCellStyle(boldStyle);

            sheet.createFreezePane(0, rowIdx);

            // Data rows
            for (Row row : model.rows()) {
                org.apache.poi.ss.usermodel.Row xlRow = sheet.createRow(rowIdx++);
                CellStyle style = pickStyle(row, normalStyle, headerStyle, subtotalSt, reconOkSt, reconBadSt);
                String label    = buildLabel(row);

                Cell labelCell = xlRow.createCell(0);
                labelCell.setCellValue(label);
                labelCell.setCellStyle(style);

                writeAmount(xlRow, 1, row.current(), style);
                writeAmount(xlRow, 2, row.comparative(), style);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("XLSX generation failed", e);
        }
    }

    // -------------------------------------------------------------------------

    private void writeHeaderRow(Sheet sheet, int rowIdx, String text, CellStyle style) {
        org.apache.poi.ss.usermodel.Row r = sheet.createRow(rowIdx);
        Cell c = r.createCell(0);
        c.setCellValue(text);
        c.setCellStyle(style);
    }

    private void writeAmount(org.apache.poi.ss.usermodel.Row row, int col,
                              BigDecimal amt, CellStyle style) {
        Cell cell = row.createCell(col);
        if (amt != null) cell.setCellValue(amt.doubleValue());
        cell.setCellStyle(style);
    }

    private CellStyle pickStyle(Row row,
                                 CellStyle normal, CellStyle header,
                                 CellStyle subtotal, CellStyle reconOk, CellStyle reconBad) {
        return switch (row.rowType()) {
            case SECTION_HEADER     -> header;
            case SUBTOTAL, TOTAL    -> subtotal;
            case RECONCILIATION     -> row.ties() ? reconOk : reconBad;
            default                 -> normal;
        };
    }

    private String buildLabel(Row row) {
        String label = row.label() != null ? row.label() : "";
        return row.rowType() == StatementRenderModel.RowType.LINE ? "  " + label : label;
    }

    private CellStyle boldStyle(Workbook wb, boolean shaded, IndexedColors bg) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        if (shaded) {
            s.setFillForegroundColor(bg.getIndex());
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        return s;
    }

    private CellStyle normalStyle(Workbook wb) {
        return wb.createCellStyle();
    }

    private CellStyle reconStyle(Workbook wb, IndexedColors bg) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setFillForegroundColor(bg.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }
}
