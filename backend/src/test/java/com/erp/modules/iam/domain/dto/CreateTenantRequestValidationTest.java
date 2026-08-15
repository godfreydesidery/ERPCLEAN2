package com.erp.modules.iam.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Bean Validation on {@link CreateTenantRequest}'s optional components.
 *
 * <p>Two things are asserted, and both are the kind that only break in production. First, that an
 * existing caller — every one of which omits the new fields, since the endpoint has no client but
 * curl and Swagger — cannot start receiving a 400. {@code @NotBlank}, {@code @NotNull} and
 * {@code @Size(min = …)} all reject a value that the design says means "create nothing", so this is
 * the test that goes red the day one is added by habit.
 *
 * <p>Second, that each {@code @Size} matches its column exactly. A cap looser than the column turns
 * a typo into a Postgres 22001 raised MID-TRANSACTION, after the organisation and company rows have
 * been written — a 500 in place of a clean 400 at the edge.
 */
class CreateTenantRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    @DisplayName("omitting every optional field is valid — an existing caller cannot start failing")
    void allOptionalFieldsNullIsValid() {
        assertThat(validator.validate(with(null, null, null, null, null, null, null))).isEmpty();
    }

    @Test
    @DisplayName("empty strings are valid too — blank means 'create nothing', not 'reject'")
    void allOptionalFieldsEmptyIsValid() {
        assertThat(validator.validate(with("", "", "", "", "", "", ""))).isEmpty();
    }

    @ParameterizedTest(name = "{0} accepts {1} characters and rejects {2}")
    @CsvSource({
            "companyLegalName,   200, 201",
            "companyTaxId,        60,  61",
            "companyVrn,          40,  41",
            "priceListCode,       20,  21",
            "priceListName,      120, 121",
            "walkInCustomerName, 200, 201",
            "posTillName,         60,  61",
    })
    void sizeCapsMatchTheirColumns(String field, int atCap, int overCap) {
        assertThat(validator.validate(withOnly(field, "x".repeat(atCap))))
                .as("%s at its column width must be accepted", field)
                .isEmpty();

        assertThat(validator.validate(withOnly(field, "x".repeat(overCap))))
                .as("%s past its column width must be rejected at the edge, not by Postgres "
                        + "mid-transaction", field)
                .isNotEmpty();
    }

    // -------------------------------------------------------------------------

    private static CreateTenantRequest withOnly(String field, String value) {
        return with(
                "companyLegalName".equals(field) ? value : null,
                "companyTaxId".equals(field) ? value : null,
                "companyVrn".equals(field) ? value : null,
                "priceListCode".equals(field) ? value : null,
                "priceListName".equals(field) ? value : null,
                "walkInCustomerName".equals(field) ? value : null,
                "posTillName".equals(field) ? value : null);
    }

    private static CreateTenantRequest with(String legalName, String taxId, String vrn,
                                            String priceListCode, String priceListName,
                                            String walkInName, String tillName) {
        return new CreateTenantRequest(
                "Kilimanjaro Group", "Africa/Dar_es_Salaam",
                "KS", "Kilimanjaro Supermarket",
                legalName, taxId, vrn,
                "HQ", "Head Office",
                "orgadmin", "OrgPass12345", "Org Admin",
                "TZS", "TZS", List.of("TZS"),
                priceListCode, priceListName, null, walkInName, tillName);
    }
}
