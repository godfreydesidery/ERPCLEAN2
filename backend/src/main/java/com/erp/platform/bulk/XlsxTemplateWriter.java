package com.erp.platform.bulk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Builds an XLSX import template from a handler's {@link ColumnSpec} list (Apache POI — already a
 * dependency, ADR-0018 D-9). Sheet 1 "Data" is the fill-in grid: a frozen, bold, shaded header with
 * required columns asterisked and choice columns backed by a dropdown; sheet 2 "Instructions"
 * documents every column. Data validation is wired to the first {@value #VALIDATED_ROWS} rows.
 */
@Component
public class XlsxTemplateWriter {

    /** How many data rows get dropdowns wired up (well above any sane hand-import). */
    private static final int VALIDATED_ROWS = 2000;

    /** A blank fill-in template. */
    public byte[] write(String title, List<ColumnSpec> columns) {
        return write(title, columns, List.of());
    }

    /**
     * A template pre-filled with {@code dataRows} (the export / download-current round-trip). Each
     * row maps column header → cell value; missing keys write a blank cell.
     */
    public byte[] write(String title, List<ColumnSpec> columns,
                        List<? extends Map<String, String>> dataRows) {
        try (Workbook wb = new XSSFWorkbook()) {
            writeDataSheet(wb, columns, dataRows);
            writeInstructionsSheet(wb, title, columns);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Template generation failed", e);
        }
    }

    private void writeDataSheet(Workbook wb, List<ColumnSpec> columns,
                                List<? extends Map<String, String>> dataRows) {
        Sheet sheet = wb.createSheet("Data");
        CellStyle headerStyle = headerStyle(wb);
        CellStyle refHeaderStyle = referenceHeaderStyle(wb);
        CellStyle refDataStyle = referenceDataStyle(wb);
        Row header = sheet.createRow(0);
        for (int c = 0; c < columns.size(); c++) {
            ColumnSpec col = columns.get(c);
            Cell cell = header.createCell(c);
            cell.setCellValue(col.required() ? "* " + col.header() : col.header());
            cell.setCellStyle(col.reference() ? refHeaderStyle : headerStyle);
            sheet.setColumnWidth(c, 22 * 256);
            if (col.allowedValues() != null && !col.allowedValues().isEmpty()) {
                addDropdown(sheet, c, col.allowedValues());
            }
        }
        sheet.createFreezePane(0, 1);

        int r = 1;
        for (Map<String, String> data : dataRows) {
            Row row = sheet.createRow(r++);
            for (int c = 0; c < columns.size(); c++) {
                ColumnSpec col = columns.get(c);
                String value = data.get(col.header());
                Cell cell = row.createCell(c);
                cell.setCellValue(value == null ? "" : value);
                // Reference columns are read-only context — tint them so the editor sees at a
                // glance which cells to change (white) and which are just information (blue).
                if (col.reference()) {
                    cell.setCellStyle(refDataStyle);
                }
            }
        }
    }

    private void addDropdown(Sheet sheet, int colIndex, List<String> values) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint =
                helper.createExplicitListConstraint(values.toArray(new String[0]));
        CellRangeAddressList range = new CellRangeAddressList(1, VALIDATED_ROWS, colIndex, colIndex);
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setSuppressDropDownArrow(true);
        validation.setShowErrorBox(true);
        sheet.addValidationData(validation);
    }

    private void writeInstructionsSheet(Workbook wb, String title, List<ColumnSpec> columns) {
        Sheet sheet = wb.createSheet("Instructions");
        sheet.setColumnWidth(0, 30 * 256);
        sheet.setColumnWidth(1, 12 * 256);
        sheet.setColumnWidth(2, 80 * 256);
        CellStyle bold = boldStyle(wb);

        int r = 0;
        Cell t = sheet.createRow(r++).createCell(0);
        t.setCellValue(title + " — import template");
        t.setCellStyle(bold);
        r++; // blank

        sheet.createRow(r++).createCell(0).setCellValue(
                "Fill in the 'Data' sheet, one record per row, then upload. Columns marked * are "
              + "required. On an update (a row whose code matches an existing record), a blank "
              + "optional cell keeps the current value. Blue 'Reference' columns are read-only "
              + "context (e.g. the product name) — edit the white cells; reference values are ignored.");
        r++; // blank

        Row colHead = sheet.createRow(r++);
        writeBold(colHead, 0, "Column", bold);
        writeBold(colHead, 1, "Required", bold);
        writeBold(colHead, 2, "Notes", bold);

        for (ColumnSpec col : columns) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(col.header());
            row.createCell(1).setCellValue(requiredLabel(col));
            row.createCell(2).setCellValue(columnNote(col));
        }
    }

    /** "Reference" for a read-only column, else "Yes"/"No" for the required flag. */
    private static String requiredLabel(ColumnSpec col) {
        if (col.reference()) {
            return "Reference";
        }
        return col.required() ? "Yes" : "No";
    }

    /** The Instructions note: read-only prefix (reference), the help text, and any allowed values. */
    private static String columnNote(ColumnSpec col) {
        String note = col.help() == null ? "" : col.help();
        if (col.reference()) {
            note = ("Read-only — shown for context; not imported. " + note).trim();
        }
        if (col.allowedValues() != null && !col.allowedValues().isEmpty()) {
            note = note.isBlank() ? "" : note + " ";
            note += "Allowed: " + String.join(", ", col.allowedValues()) + ".";
        }
        return note;
    }

    private static void writeBold(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    private CellStyle headerStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    /** Header style for a read-only reference column — bold italic on a light-blue fill. */
    private CellStyle referenceHeaderStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setItalic(true);
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    /** Light-blue fill for reference (read-only) data cells, so they read as "information, not input". */
    private CellStyle referenceDataStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    private CellStyle boldStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        return s;
    }
}
