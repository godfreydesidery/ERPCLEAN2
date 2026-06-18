package com.erp.modules.ap.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Bean-validation unit tests for BillLineRequest — issue #18:
 * negative unitCostAmount must produce a constraint violation (clean 400)
 * before the request reaches the service, mirroring the existing @Positive on billedQty.
 */
class BillLineRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void negativeUnitCostAmount_violatesPositiveOrZero() {
        BillLineRequest req = new BillLineRequest(
                null, null, null, "Widget",
                new BigDecimal("2"), new BigDecimal("-100.00"));

        Set<ConstraintViolation<BillLineRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("unitCostAmount");
    }

    @Test
    void zeroUnitCostAmount_noViolation() {
        // Zero cost is valid (e.g. free samples, promotional items).
        BillLineRequest req = new BillLineRequest(
                null, null, null, "Free sample",
                new BigDecimal("1"), BigDecimal.ZERO);

        Set<ConstraintViolation<BillLineRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .doesNotContain("unitCostAmount");
    }

    @Test
    void positiveUnitCostAmount_noViolation() {
        BillLineRequest req = new BillLineRequest(
                null, null, null, "Widget",
                new BigDecimal("2"), new BigDecimal("50.00"));

        Set<ConstraintViolation<BillLineRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .doesNotContain("unitCostAmount");
    }

    @Test
    void negativeQty_stillViolatesPositive() {
        // Confirm existing billedQty @Positive still fires (regression guard).
        BillLineRequest req = new BillLineRequest(
                null, null, null, "Widget",
                new BigDecimal("-2"), new BigDecimal("50.00"));

        Set<ConstraintViolation<BillLineRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("billedQty");
    }
}
