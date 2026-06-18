package com.erp.modules.ar.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ar.domain.dto.RecordReceiptRequest.AllocationLineRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Bean-validation unit tests for RecordReceiptRequest — issue #16:
 * negative/zero receipt amount and negative allocation amount must produce
 * constraint violations (clean 400) before the request reaches the service.
 */
class RecordReceiptRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // -------------------------------------------------------------------------
    // Receipt-level amount
    // -------------------------------------------------------------------------

    @Test
    void negativeAmount_violatesPositive() {
        RecordReceiptRequest req = new RecordReceiptRequest(
                "CO-1", "CUST-1", new BigDecimal("-5000"),
                "TZS", LocalDate.now(), "CASH", null, List.of());

        Set<ConstraintViolation<RecordReceiptRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("amount");
    }

    @Test
    void zeroAmount_violatesPositive() {
        RecordReceiptRequest req = new RecordReceiptRequest(
                "CO-1", "CUST-1", BigDecimal.ZERO,
                "TZS", LocalDate.now(), "CASH", null, List.of());

        Set<ConstraintViolation<RecordReceiptRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("amount");
    }

    @Test
    void positiveAmount_noViolation() {
        RecordReceiptRequest req = new RecordReceiptRequest(
                "CO-1", "CUST-1", new BigDecimal("1000"),
                "TZS", LocalDate.now(), "CASH", null, List.of());

        Set<ConstraintViolation<RecordReceiptRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .doesNotContain("amount");
    }

    // -------------------------------------------------------------------------
    // Allocation-line amount (@Valid cascade)
    // -------------------------------------------------------------------------

    @Test
    void negativeAllocatedAmount_violatesPositiveViaValidCascade() {
        AllocationLineRequest badAlloc = new AllocationLineRequest("INV-UID-1", new BigDecimal("-500"));
        RecordReceiptRequest req = new RecordReceiptRequest(
                "CO-1", "CUST-1", new BigDecimal("1000"),
                "TZS", LocalDate.now(), "CASH", null, List.of(badAlloc));

        Set<ConstraintViolation<RecordReceiptRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("allocatedAmount"));
    }

    @Test
    void zeroAllocatedAmount_violatesPositiveViaValidCascade() {
        AllocationLineRequest badAlloc = new AllocationLineRequest("INV-UID-1", BigDecimal.ZERO);
        RecordReceiptRequest req = new RecordReceiptRequest(
                "CO-1", "CUST-1", new BigDecimal("1000"),
                "TZS", LocalDate.now(), "CASH", null, List.of(badAlloc));

        Set<ConstraintViolation<RecordReceiptRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("allocatedAmount"));
    }

    @Test
    void positiveAllocatedAmount_noViolation() {
        AllocationLineRequest goodAlloc = new AllocationLineRequest("INV-UID-1", new BigDecimal("500"));
        RecordReceiptRequest req = new RecordReceiptRequest(
                "CO-1", "CUST-1", new BigDecimal("1000"),
                "TZS", LocalDate.now(), "CASH", null, List.of(goodAlloc));

        Set<ConstraintViolation<RecordReceiptRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .doesNotContain("amount")
                .noneMatch(p -> p.contains("allocatedAmount"));
    }
}
