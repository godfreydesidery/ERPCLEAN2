package com.erp.modules.reporting.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * XLSX renderer for {@link TabularRenderModel} using Apache POI. Sibling of
 * {@link XlsxStatementRenderer} (fixed 3-column financial shape); this one handles an arbitrary
 * number of columns, one cell style per column (created ONCE, reused for every row — a workbook
 * has a hard cap on distinct cell styles, and this report can be long).
 */
@Component
public class TabularXlsxRenderer {

    public byte[] render(TabularRenderModel model) {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Report");

            CellStyle titleStyle  = boldStyle(wb, false, null);
            CellStyle normalStyle = wb.createCellStyle();
            CellStyle headerStyle = boldStyle(wb, true, IndexedColors.GREY_25_PERCENT);

            int columnCount = model.columns().size();

            int rowIdx = 0;
            rowIdx = writeTextRow(sheet, rowIdx, model.title(), titleStyle);
            if (model.headerLines() != null) {
                for (String line : model.headerLines()) {
                    rowIdx = writeTextRow(sheet, rowIdx, line, normalStyle);
                }
            }
            rowIdx = writeTextRow(sheet, rowIdx, "Generated: " + model.generatedAt(), normalStyle);
            rowIdx = writeTextRow(sheet, rowIdx, "", normalStyle);

            // Column headings
            Row headRow = sheet.createRow(rowIdx++);
            for (int c = 0; c < columnCount; c++) {
                Cell cell = headRow.createCell(c);
                cell.setCellValue(model.columns().get(c).header());
                cell.setCellStyle(headerStyle);
            }
            sheet.createFreezePane(0, rowIdx);

            // One data style + one totals style PER COLUMN, created once (not per row/cell).
            CellStyle[] dataStyles   = new CellStyle[columnCount];
            CellStyle[] totalsStyles = new CellStyle[columnCount];
            for (int c = 0; c < columnCount; c++) {
                HorizontalAlignment align = toPoiAlign(model.columns().get(c).align());
                dataStyles[c]   = alignedStyle(wb, align, false);
                totalsStyles[c] = alignedStyle(wb, align, true);
            }

            for (List<String> dataRow : model.rows()) {
                Row xlRow = sheet.createRow(rowIdx++);
                for (int c = 0; c < dataRow.size(); c++) {
                    Cell cell = xlRow.createCell(c);
                    cell.setCellValue(dataRow.get(c));
                    cell.setCellStyle(dataStyles[c]);
                }
            }

            if (model.totalsRow() != null) {
                Row totalsXlRow = sheet.createRow(rowIdx++);
                for (int c = 0; c < model.totalsRow().size(); c++) {
                    Cell cell = totalsXlRow.createCell(c);
                    cell.setCellValue(model.totalsRow().get(c));
                    cell.setCellStyle(totalsStyles[c]);
                }
            }

            for (int c = 0; c < columnCount; c++) {
                sheet.setColumnWidth(c, 4500);
            }
            if (columnCount > 0) {
                sheet.setColumnWidth(0, 5000);
            }
            if (columnCount > 1) {
                sheet.setColumnWidth(1, 12000);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("XLSX generation failed", e);
        }
    }

    // -------------------------------------------------------------------------

    private int writeTextRow(Sheet sheet, int rowIdx, String text, CellStyle style) {
        Row r = sheet.createRow(rowIdx);
        Cell c = r.createCell(0);
        c.setCellValue(text != null ? text : "");
        c.setCellStyle(style);
        return rowIdx + 1;
    }

    private HorizontalAlignment toPoiAlign(TabularRenderModel.Align align) {
        return switch (align) {
            case LEFT   -> HorizontalAlignment.LEFT;
            case RIGHT  -> HorizontalAlignment.RIGHT;
            case CENTER -> HorizontalAlignment.CENTER;
        };
    }

    private CellStyle alignedStyle(Workbook wb, HorizontalAlignment align, boolean bold) {
        CellStyle s = wb.createCellStyle();
        s.setAlignment(align);
        if (bold) {
            Font f = wb.createFont();
            f.setBold(true);
            s.setFont(f);
            s.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        }
        return s;
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
}
