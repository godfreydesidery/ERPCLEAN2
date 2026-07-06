package com.erp.platform.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reference (read-only) columns — e.g. the product name/cost shown beside a price — must be written
 * into the template, pre-fillable on export, and read back cleanly on re-upload. A handler simply
 * chooses not to consume them, so their value never affects the import.
 */
class ReferenceColumnTest {

    private final XlsxTemplateWriter writer = new XlsxTemplateWriter();
    private final XlsxRowReader reader = new XlsxRowReader();

    @Test
    void referenceFactory_isReferenceNotRequiredNoDropdown() {
        ColumnSpec c = ColumnSpec.reference("Product Name", "for context");

        assertThat(c.reference()).isTrue();
        assertThat(c.required()).isFalse();
        assertThat(c.allowedValues()).isNull();
    }

    @Test
    void referenceColumns_areExportedAndReadBackAlongsideEditableColumns() {
        List<ColumnSpec> cols = List.of(
                ColumnSpec.of("Product Code", true, "code"),
                ColumnSpec.reference("Product Name", "name, for context"),
                ColumnSpec.of("Amount", false, "the price to edit"),
                ColumnSpec.reference("Cost", "current cost"));

        LinkedHashMap<String, String> row = new LinkedHashMap<>();
        row.put("Product Code", "PROD-1");
        row.put("Product Name", "Widget 500ml");
        row.put("Amount", "1500");
        row.put("Cost", "1000");

        byte[] xlsx = writer.write("Product prices", cols, List.of(row));
        List<ImportRow> parsed = reader.read(new ByteArrayInputStream(xlsx));

        assertThat(parsed).hasSize(1);
        ImportRow r = parsed.get(0);
        // Every value round-trips: the reference Name/Cost are present (so a handler can show them),
        // and the reader does not choke on the reference headers. Editable columns are unchanged.
        assertThat(r.get("Product Code")).isEqualTo("PROD-1");
        assertThat(r.get("Product Name")).isEqualTo("Widget 500ml");
        assertThat(r.get("Amount")).isEqualTo("1500");
        assertThat(r.get("Cost")).isEqualTo("1000");
    }
}
