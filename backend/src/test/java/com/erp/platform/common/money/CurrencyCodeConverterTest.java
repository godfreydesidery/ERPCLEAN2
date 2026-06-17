package com.erp.platform.common.money;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link CurrencyCodeConverter} (ADR-0039 D-1).
 */
class CurrencyCodeConverterTest {

    private final CurrencyCodeConverter converter = new CurrencyCodeConverter();

    // ---- toDatabaseColumn -------------------------------------------------------

    @Test
    void toDatabaseColumn_validCode_returnsString() {
        assertThat(converter.convertToDatabaseColumn(CurrencyCode.of("TZS"))).isEqualTo("TZS");
    }

    @Test
    void toDatabaseColumn_null_returnsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    // ---- toEntityAttribute ------------------------------------------------------

    @Test
    void toEntityAttribute_validString_returnsCurrencyCode() {
        CurrencyCode cc = converter.convertToEntityAttribute("KES");
        assertThat(cc).isNotNull();
        assertThat(cc.value()).isEqualTo("KES");
    }

    @Test
    void toEntityAttribute_null_returnsNull() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void toEntityAttribute_blank_returnsNull() {
        assertThat(converter.convertToEntityAttribute("   ")).isNull();
    }

    // ---- round-trip ------------------------------------------------------------

    @Test
    void roundTrip_preservesCode() {
        CurrencyCode original = CurrencyCode.of("USD");
        String dbValue = converter.convertToDatabaseColumn(original);
        CurrencyCode restored = converter.convertToEntityAttribute(dbValue);
        assertThat(restored).isEqualTo(original);
    }
}
