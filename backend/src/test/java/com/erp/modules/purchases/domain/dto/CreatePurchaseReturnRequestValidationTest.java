package com.erp.modules.purchases.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.purchases.domain.dto.CreatePurchaseReturnRequest.ReturnLineRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Bean-validation unit tests for CreatePurchaseReturnRequest — issue #19:
 * negative/zero returnedQty must produce a constraint violation via the @Valid cascade
 * that was previously missing from the controller (clean 400 instead of DB 500).
 */
class CreatePurchaseReturnRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void negativeReturnedQty_violatesPositiveViaCascade() {
        ReturnLineRequest badLine = new ReturnLineRequest("GRL-UID-1", new BigDecimal("-5"));
        CreatePurchaseReturnRequest req = new CreatePurchaseReturnRequest(
                "CO-1", "GR-UID-1", "Damaged goods", List.of(badLine));

        Set<ConstraintViolation<CreatePurchaseReturnRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("returnedQty"));
    }

    @Test
    void zeroReturnedQty_violatesPositiveViaCascade() {
        ReturnLineRequest badLine = new ReturnLineRequest("GRL-UID-1", BigDecimal.ZERO);
        CreatePurchaseReturnRequest req = new CreatePurchaseReturnRequest(
                "CO-1", "GR-UID-1", "Damaged goods", List.of(badLine));

        Set<ConstraintViolation<CreatePurchaseReturnRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("returnedQty"));
    }

    @Test
    void positiveReturnedQty_noViolation() {
        ReturnLineRequest goodLine = new ReturnLineRequest("GRL-UID-1", new BigDecimal("3"));
        CreatePurchaseReturnRequest req = new CreatePurchaseReturnRequest(
                "CO-1", "GR-UID-1", "Damaged goods", List.of(goodLine));

        Set<ConstraintViolation<CreatePurchaseReturnRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .noneMatch(p -> p.contains("returnedQty"));
    }

    @Test
    void emptyLines_violatesNotEmpty() {
        CreatePurchaseReturnRequest req = new CreatePurchaseReturnRequest(
                "CO-1", "GR-UID-1", "reason", List.of());

        Set<ConstraintViolation<CreatePurchaseReturnRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("lines");
    }
}
