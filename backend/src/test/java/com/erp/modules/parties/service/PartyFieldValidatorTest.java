package com.erp.modules.parties.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the shared party field guards (UAT 2026-08-12).
 *
 * <p>These live-fire defects are what this class exists to prevent: {@code country} is
 * {@code VARCHAR(2)}, the API accepted the country NAME, and Postgres refused it at flush — so the
 * caller got "The supplied value violates a data constraint. Check field formats, lengths, and
 * numeric ranges.", which names no field and offers no remedy. A procurement officer only got past
 * it by reading the source.
 *
 * <p>The validator is now shared by the supplier AND customer paths, so it is tested here directly
 * rather than only through one caller: a guard used by two callers should not be pinned by one.
 *
 * <p>What each test protects: the message NAMES the field, shows the expected FORM with a worked
 * example, and echoes back what the user actually typed — the three things that turn a dead end
 * into something a shop office can act on.
 */
class PartyFieldValidatorTest {

    @Nested
    @DisplayName("country")
    class Country {

        @Test
        void acceptsAValidCodeAndUppercasesIt() {
            assertThat(PartyFieldValidator.isoCountryCode("TZ")).isEqualTo("TZ");
            // A user typing lower case is not making a mistake worth a 400.
            assertThat(PartyFieldValidator.isoCountryCode("tz")).isEqualTo("TZ");
            assertThat(PartyFieldValidator.isoCountryCode("  ke  ")).isEqualTo("KE");
        }

        @Test
        void treatsAbsentAndBlankAsNotSupplied() {
            assertThat(PartyFieldValidator.isoCountryCode(null)).isNull();
            assertThat(PartyFieldValidator.isoCountryCode("")).isNull();
            assertThat(PartyFieldValidator.isoCountryCode("   ")).isNull();
        }

        @Test
        void refusesTheCountryNameWithAnActionableMessage() {
            assertThatThrownBy(() -> PartyFieldValidator.isoCountryCode("Tanzania"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Country")        // names the field
                    .hasMessageContaining("two-letter")     // states the expected form
                    .hasMessageContaining("TZ")             // gives a worked example
                    .hasMessageContaining("Tanzania");      // echoes what was typed
        }

        @Test
        void refusesTheWrongLengthAndNonLetters() {
            assertThatThrownBy(() -> PartyFieldValidator.isoCountryCode("T"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PartyFieldValidator.isoCountryCode("TZA"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PartyFieldValidator.isoCountryCode("12"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void doesNotLetAPastedBlobBloatTheError() {
            String blob = "Tanzania".repeat(50);
            assertThatThrownBy(() -> PartyFieldValidator.isoCountryCode(blob))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(300));
        }
    }

    @Nested
    @DisplayName("currency")
    class Currency {

        @Test
        void acceptsAValidCodeAndTreatsBlankAsNotSupplied() {
            assertThat(PartyFieldValidator.currencyCode("TZS", "Default currency")).isNotNull();
            assertThat(PartyFieldValidator.currencyCode(null, "Default currency")).isNull();
            assertThat(PartyFieldValidator.currencyCode("  ", "Default currency")).isNull();
        }

        @Test
        void refusesWithTheFieldsOwnNameRatherThanAJavaTypeName() {
            assertThatThrownBy(
                    () -> PartyFieldValidator.currencyCode("Shillings", "Default currency"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Default currency")   // the label the user sees on screen
                    .hasMessageContaining("three-letter")
                    .hasMessageContaining("TZS")
                    // the wrapped type's own wording names a class, which means nothing to a user
                    .hasMessageNotContaining("CurrencyCode");
        }
    }

    @Nested
    @DisplayName("maxLength")
    class MaxLength {

        @Test
        void passesAValueThroughUnchangedSoItCanBeUsedInline() {
            assertThat(PartyFieldValidator.maxLength("Kilimanjaro", 200, "Supplier name"))
                    .isEqualTo("Kilimanjaro");
            assertThatCode(() -> PartyFieldValidator.maxLength(null, 200, "Supplier name"))
                    .doesNotThrowAnyException();
        }

        @Test
        void namesTheFieldTheLimitAndWhatWasActuallySent() {
            assertThatThrownBy(() -> PartyFieldValidator.maxLength("x".repeat(214), 200, "Supplier name"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Supplier name")
                    .hasMessageContaining("200")
                    .hasMessageContaining("214");
        }
    }
}
