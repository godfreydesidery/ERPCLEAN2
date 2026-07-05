package com.erp.platform.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for the user-safe cell parsers (the fiddly, reusable bit of the bulk framework). */
class ImportParsersTest {

    private enum Colour { RED, DARK_BLUE }

    private static ImportRow row(String header, String value) {
        return new ImportRow(2, Map.of(header, value));
    }

    @Test
    void requireText_rejectsBlank() {
        assertThatThrownBy(() -> ImportParsers.requireText(row("Name", "  "), "Name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Name");
    }

    @Test
    void text_blankBecomesNull() {
        assertThat(ImportParsers.text(row("X", ""), "X")).isNull();
        assertThat(ImportParsers.text(row("X", " v "), "X")).isEqualTo("v");
    }

    @Test
    void parseBool_acceptsCommonForms() {
        assertThat(ImportParsers.parseBool(row("B", "Yes"), "B", null)).isTrue();
        assertThat(ImportParsers.parseBool(row("B", "no"), "B", null)).isFalse();
        assertThat(ImportParsers.parseBool(row("B", "1"), "B", null)).isTrue();
        assertThat(ImportParsers.parseBool(row("B", ""), "B", Boolean.FALSE)).isFalse();
    }

    @Test
    void parseBool_rejectsGarbage() {
        assertThatThrownBy(() -> ImportParsers.parseBool(row("B", "maybe"), "B", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Yes or No");
    }

    @Test
    void parseEnum_isCaseAndSeparatorInsensitive() {
        assertThat(ImportParsers.parseEnum(Colour.class, row("C", "red"), "C", null)).isEqualTo(Colour.RED);
        assertThat(ImportParsers.parseEnum(Colour.class, row("C", "dark blue"), "C", null)).isEqualTo(Colour.DARK_BLUE);
        assertThat(ImportParsers.parseEnum(Colour.class, row("C", "dark-blue"), "C", null)).isEqualTo(Colour.DARK_BLUE);
        assertThat(ImportParsers.parseEnum(Colour.class, row("C", ""), "C", Colour.RED)).isEqualTo(Colour.RED);
    }

    @Test
    void parseEnum_rejectsUnknownWithAllowedList() {
        assertThatThrownBy(() -> ImportParsers.parseEnumRequired(Colour.class, row("C", "green"), "C"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RED")
                .hasMessageContaining("DARK_BLUE");
    }

    @Test
    void parseDecimal_toleratesThousandsSeparators() {
        assertThat(ImportParsers.parseDecimal(row("A", "1,500.50"), "A")).isEqualByComparingTo(new BigDecimal("1500.50"));
        assertThat(ImportParsers.parseDecimal(row("A", ""), "A")).isNull();
    }

    @Test
    void parseInt_rejectsNonInteger() {
        assertThatThrownBy(() -> ImportParsers.parseInt(row("N", "3.5"), "N"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ImportParsers.parseInt(row("N", "30"), "N")).isEqualTo(30);
    }

    @Test
    void isoCode2_upperCasesTwoLetters_andRejectsFullNames() {
        assertThat(ImportParsers.isoCode2(row("Country", "tz"), "Country")).isEqualTo("TZ");
        assertThat(ImportParsers.isoCode2(row("Country", ""), "Country")).isNull();
        assertThatThrownBy(() -> ImportParsers.isoCode2(row("Country", "Tanzania"), "Country"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2-letter ISO code");
    }
}
