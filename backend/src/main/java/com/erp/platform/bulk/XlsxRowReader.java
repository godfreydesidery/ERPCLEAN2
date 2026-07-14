package com.erp.platform.bulk;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/**
 * Reads an uploaded XLSX/XLS back into {@link ImportRow}s. The first sheet's first row is the
 * header (a leading "* " marker on a required column is stripped so headers match the template
 * spec); every subsequent non-blank row becomes an ImportRow keyed by header. Capped at
 * {@value #MAX_ROWS} data rows so a runaway upload cannot exhaust memory (processing is synchronous).
 */
@Component
public class XlsxRowReader {

    /** Hard cap on data rows in one upload. */
    public static final int MAX_ROWS = 2000;

    private final DataFormatter formatter = new DataFormatter();

    /**
     * A parsed upload: the header row as written in the file (required-marker stripped) plus the
     * data rows. The headers are what lets the caller tell a mis-picked entity ("this is a stock
     * sheet, but you chose Customers") apart from a genuinely incomplete row.
     */
    public record ParsedSheet(List<String> headers, List<ImportRow> rows) {
    }

    /** The data rows only — for callers that don't need to inspect the header row. */
    public List<ImportRow> read(InputStream in) {
        return readSheet(in).rows();
    }

    public ParsedSheet readSheet(InputStream in) {
        try (Workbook wb = WorkbookFactory.create(in)) {
            if (wb.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("The uploaded file has no sheets.");
            }
            Sheet sheet = wb.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new IllegalArgumentException("The uploaded file has no header row.");
            }
            List<String> headers = readHeaders(headerRow);

            List<ImportRow> rows = new ArrayList<>();
            int lastRow = sheet.getLastRowNum();
            for (int r = headerRow.getRowNum() + 1; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<String, String> cells = new LinkedHashMap<>();
                boolean anyValue = false;
                for (int c = 0; c < headers.size(); c++) {
                    String header = headers.get(c);
                    if (header.isBlank()) {
                        continue;
                    }
                    Cell cell = row.getCell(c);
                    String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                    if (!value.isEmpty()) {
                        anyValue = true;
                    }
                    cells.put(header, value);
                }
                if (!anyValue) {
                    continue; // skip fully-blank rows
                }
                rows.add(new ImportRow(r + 1, cells)); // +1 → 1-based spreadsheet row number
                if (rows.size() > MAX_ROWS) {
                    throw new IllegalArgumentException(
                            "The file has more than " + MAX_ROWS + " rows. Split it into smaller files.");
                }
            }
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("The file has no data rows to import.");
            }
            return new ParsedSheet(headers, rows);
        } catch (IOException e) {
            throw new IllegalArgumentException("The file could not be read as a spreadsheet.");
        }
    }

    private List<String> readHeaders(Row headerRow) {
        List<String> headers = new ArrayList<>();
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            String h = cell == null ? "" : formatter.formatCellValue(cell).trim();
            if (h.startsWith("*")) {
                h = h.substring(1).trim();
            }
            headers.add(h);
        }
        return headers;
    }
}
