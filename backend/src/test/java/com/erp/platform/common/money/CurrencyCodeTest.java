package com.erp.platform.common.money;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link CurrencyCode} value type (ADR-0039 D-1).
 */
class CurrencyCodeTest {

    // ---- factory / normalisation ------------------------------------------------

    @Test
    void of_uppercase_accepted() {
        assertThat(CurrencyCode.of("TZS").value()).isEqualTo("TZS");
    }

    @Test
    void of_lowercase_normalised() {
        assertThat(CurrencyCode.of("tzs").value()).isEqualTo("TZS");
    }

    @Test
    void of_mixedCase_normalised() {
        assertThat(CurrencyCode.of("uSd").value()).isEqualTo("USD");
    }

    @Test
    void of_withLeadingTrailingWhitespace_normalised() {
        assertThat(CurrencyCode.of("  EUR  ").value()).isEqualTo("EUR");
    }

    // ---- validation rejections --------------------------------------------------

    @Test
    void of_null_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CurrencyCode.of(null))
                .withMessageContaining("null or blank");
    }

    @Test
    void of_blank_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CurrencyCode.of("   "))
                .withMessageContaining("null or blank");
    }

    @Test
    void of_twoLetters_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CurrencyCode.of("TZ"))
                .withMessageContaining("3 ASCII letters");
    }

    @Test
    void of_fourLetters_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CurrencyCode.of("TZSH"))
                .withMessageContaining("3 ASCII letters");
    }

    @Test
    void of_digits_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CurrencyCode.of("123"))
                .withMessageContaining("3 ASCII letters");
    }

    @Test
    void of_specialChars_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CurrencyCode.of("TZ$"))
                .withMessageContaining("3 ASCII letters");
    }

    // ---- equality / hashCode ----------------------------------------------------

    @Test
    void equals_sameCode_true() {
        assertThat(CurrencyCode.of("TZS")).isEqualTo(CurrencyCode.of("TZS"));
    }

    @Test
    void equals_differentCase_equalAfterNormalisation() {
        assertThat(CurrencyCode.of("tzs")).isEqualTo(CurrencyCode.of("TZS"));
    }

    @Test
    void equals_differentCode_false() {
        assertThat(CurrencyCode.of("TZS")).isNotEqualTo(CurrencyCode.of("USD"));
    }

    @Test
    void hashCode_sameCode_equal() {
        assertThat(CurrencyCode.of("TZS").hashCode()).isEqualTo(CurrencyCode.of("TZS").hashCode());
    }

    // ---- toString ---------------------------------------------------------------

    @Test
    void toString_returnsCode() {
        assertThat(CurrencyCode.of("KES").toString()).isEqualTo("KES");
    }

    // ---- Jackson serialisation --------------------------------------------------

    @Test
    void jackson_serialisesToPlainString() throws Exception {
        ObjectMapper om = new ObjectMapper();
        String json = om.writeValueAsString(CurrencyCode.of("TZS"));
        assertThat(json).isEqualTo("\"TZS\"");
    }

    @Test
    void jackson_deserialisesFromPlainString() throws Exception {
        ObjectMapper om = new ObjectMapper();
        CurrencyCode cc = om.readValue("\"USD\"", CurrencyCode.class);
        assertThat(cc.value()).isEqualTo("USD");
    }

    @Test
    void jackson_roundTrip() throws Exception {
        ObjectMapper om = new ObjectMapper();
        CurrencyCode original = CurrencyCode.of("EUR");
        String json = om.writeValueAsString(original);
        CurrencyCode restored = om.readValue(json, CurrencyCode.class);
        assertThat(restored).isEqualTo(original);
    }
}
