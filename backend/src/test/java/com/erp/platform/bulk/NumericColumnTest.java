package com.erp.platform.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

/**
 * Number columns must be written as real numeric Excel cells (not "number stored as text") so the
 * downloaded sheet supports arithmetic, SUM and formulas — while still reading back cleanly on
 * re-upload (the thousands separator the format adds is tolerated by the parsers).
 */
class NumericColumnTest {

    private final XlsxTemplateWriter writer = new XlsxTemplateWriter();
    private final XlsxRowReader reader = new XlsxRowReader();

    @Test
    void numberFactory_isNumericFreeTextNotReference() {
        ColumnSpec c = ColumnSpec.number("Cost Amount", false, "unit cost");

        assertThat(c.numeric()).isTrue();
        assertThat(c.reference()).isFalse();
        assertThat(c.required()).isFalse();
        assertThat(c.allowedValues()).isNull();
    }

    @Test
    void numberColumns_areWrittenAsNumericCells_andRoundTrip() throws Exception {
        List<ColumnSpec> cols = List.of(
                ColumnSpec.of("Product Code", true, "code"),
                ColumnSpec.number("Cost Amount", false, "unit cost"),
                ColumnSpec.of("Currency", false, "3-letter code"));

        LinkedHashMap<String, String> row = new LinkedHashMap<>();
        row.put("Product Code", "PROD-1");
        row.put("Cost Amount", "1500.5");
        row.put("Currency", "TZS");

        byte[] xlsx = writer.write("Product prices", cols, List.of(row));

        // The "Cost Amount" cell is a real number (Excel treats it as arithmetic-capable), while the
        // code and currency stay text.
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet data = wb.getSheet("Data");
            Row first = data.getRow(1); // row 0 is the header
            assertThat(first.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            Cell cost = first.getCell(1);
            assertThat(cost.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(cost.getNumericCellValue()).isEqualTo(1500.5);
            assertThat(first.getCell(2).getCellType()).isEqualTo(CellType.STRING);
        }

        // And it still survives re-upload through the real parser path.
        List<ImportRow> parsed = reader.read(new ByteArrayInputStream(xlsx));
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).get("Product Code")).isEqualTo("PROD-1");
        assertThat(ImportParsers.parseDecimal(parsed.get(0), "Cost Amount"))
                .isEqualByComparingTo("1500.5");
        assertThat(parsed.get(0).get("Currency")).isEqualTo("TZS");
    }

    @Test
    void blankNumberCell_staysBlank_notZero() throws Exception {
        List<ColumnSpec> cols = List.of(
                ColumnSpec.of("Product Code", true, "code"),
                ColumnSpec.number("Cost Amount", false, "unit cost"));

        LinkedHashMap<String, String> row = new LinkedHashMap<>();
        row.put("Product Code", "PROD-1"); // Cost Amount deliberately absent

        byte[] xlsx = writer.write("Product prices", cols, List.of(row));

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Cell cost = wb.getSheet("Data").getRow(1).getCell(1);
            // A blank number cell stays blank (not a spurious 0).
            assertThat(cost.getCellType()).isIn(CellType.BLANK, CellType.STRING);
            if (cost.getCellType() == CellType.STRING) {
                assertThat(cost.getStringCellValue()).isEmpty();
            }
        }
    }

    @Test
    void nonNumericValueInNumberColumn_fallsBackToText() throws Exception {
        // Defensive: if a value somehow isn't parseable, we still write it (as text) rather than drop it.
        List<ColumnSpec> cols = List.of(
                ColumnSpec.of("Product Code", true, "code"),
                ColumnSpec.number("Cost Amount", false, "unit cost"));

        LinkedHashMap<String, String> row = new LinkedHashMap<>();
        row.put("Product Code", "PROD-1");
        row.put("Cost Amount", "n/a");

        byte[] xlsx = writer.write("Product prices", cols, List.of(row));

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Cell cost = wb.getSheet("Data").getRow(1).getCell(1);
            assertThat(cost.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cost.getStringCellValue()).isEqualTo("n/a");
        }
    }
}
