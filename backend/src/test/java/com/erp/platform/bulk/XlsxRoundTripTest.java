package com.erp.platform.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** Template writer + row reader behave as a matched pair (headers, asterisk stripping, blank rows). */
class XlsxRoundTripTest {

    private final XlsxTemplateWriter writer = new XlsxTemplateWriter();
    private final XlsxRowReader reader = new XlsxRowReader();

    @Test
    void templateHasAsteriskedRequiredHeadersAndInstructionsSheet() throws Exception {
        byte[] xlsx = writer.write("Products", List.of(
                ColumnSpec.of("Code", false, "opt"),
                ColumnSpec.of("Name", true, "the name"),
                ColumnSpec.choice("Type", true, "kind", List.of("GOODS", "SERVICE"))));

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet data = wb.getSheet("Data");
            Row header = data.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Code");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("* Name");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("* Type");
            assertThat(wb.getSheet("Instructions")).isNotNull();
        }
    }

    @Test
    void readerStripsAsterisk_trims_andSkipsBlankRows() {
        byte[] xlsx = sheet(
                List.of("* Name", "Type"),
                List.of(
                        List.of(" Widget ", "GOODS"),
                        List.of("", ""),          // fully blank → skipped
                        List.of("Gadget", "")));

        List<ImportRow> rows = reader.read(new ByteArrayInputStream(xlsx));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("Name")).isEqualTo("Widget");     // trimmed
        assertThat(rows.get(0).get("Type")).isEqualTo("GOODS");
        assertThat(rows.get(0).rowNumber()).isEqualTo(2);            // 1-based spreadsheet row
        assertThat(rows.get(1).get("Name")).isEqualTo("Gadget");
        assertThat(rows.get(1).rowNumber()).isEqualTo(4);           // blank row 3 was skipped
    }

    @Test
    void readerRejectsFileWithNoDataRows() {
        byte[] xlsx = sheet(List.of("* Name"), List.of());
        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream(xlsx)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no data rows");
    }

    // --- helper: build a simple xlsx with a header + string data rows -----------------------------

    private static byte[] sheet(List<String> headers, List<List<String>> dataRows) {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Data");
            Row h = s.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                h.createCell(i).setCellValue(headers.get(i));
            }
            for (int r = 0; r < dataRows.size(); r++) {
                Row row = s.createRow(r + 1);
                List<String> cells = dataRows.get(r);
                for (int c = 0; c < cells.size(); c++) {
                    row.createCell(c).setCellValue(cells.get(c));
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
