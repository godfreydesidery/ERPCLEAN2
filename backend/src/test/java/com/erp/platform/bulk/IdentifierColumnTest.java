package com.erp.platform.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * PRD-08: barcodes and other digit-string identifiers typed into the template must survive Excel —
 * the template formats them as Text, and a numeric cell in such a column (or any very large
 * integral number) reads back as plain digits, never "6.00232E+12".
 */
class IdentifierColumnTest {

    private final XlsxTemplateWriter writer = new XlsxTemplateWriter();
    private final XlsxRowReader reader = new XlsxRowReader();

    @Test
    void identifierHeaders_areRecognised() {
        assertThat(ColumnSpec.of("Barcode", false, "").identifierLike()).isTrue();
        assertThat(ColumnSpec.of("Product Code", false, "").identifierLike()).isTrue();
        assertThat(ColumnSpec.of("TIN", false, "").identifierLike()).isTrue();
        assertThat(ColumnSpec.of("Phone", false, "").identifierLike()).isTrue();
        assertThat(ColumnSpec.of("Name", false, "").identifierLike()).isFalse();
        assertThat(ColumnSpec.of("Continent", false, "").identifierLike()).isFalse();
        assertThat(ColumnSpec.number("Code Count", false, "").identifierLike()).isFalse();
    }

    @Test
    void template_formatsIdentifierColumnsAsText() throws Exception {
        byte[] xlsx = writer.write("Products", List.of(
                ColumnSpec.of("Name", true, "name"),
                ColumnSpec.of("Barcode", false, "barcode")));
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet data = wb.getSheet("Data");
            assertThat(data.getColumnStyle(1).getDataFormatString()).isEqualTo("@");
            assertThat(data.getColumnStyle(0).getDataFormatString()).isNotEqualTo("@");
        }
    }

    @Test
    void numericBarcodeCell_readsBackAsPlainDigits() throws Exception {
        byte[] upload;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Data");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Name");
            h.createCell(1).setCellValue("Barcode");
            h.createCell(2).setCellValue("Code");
            h.createCell(3).setCellValue("Note");
            h.createCell(4).setCellValue("Qty");
            Row r = s.createRow(1);
            r.createCell(0).setCellValue("Serengeti Lite");
            r.createCell(1).setCellValue(6002323018469d); // typed into a General cell
            r.createCell(2).setCellValue(1234d);
            r.createCell(3).setCellValue(255712345678d); // a phone number in a free-text column
            r.createCell(4).setCellValue(2.5d);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            upload = out.toByteArray();
        }

        ImportRow row = reader.read(new ByteArrayInputStream(upload)).get(0);

        assertThat(row.get("Barcode")).isEqualTo("6002323018469");
        assertThat(row.get("Code")).isEqualTo("1234");
        assertThat(row.get("Note")).isEqualTo("255712345678");
        assertThat(row.get("Qty")).isEqualTo("2.5");
    }
}
