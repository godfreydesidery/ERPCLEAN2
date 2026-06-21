package com.erp.modules.gl.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Bean-validation unit tests for PostJournalRequest — defect #2:
 * missing companyUid or postingDate must produce constraint violations (→ 400)
 * before the request reaches the service, preventing the observed 404 (missing company)
 * and misleading 409 (missing date → period resolver blows up).
 */
class PostJournalRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    /** A minimal valid list of lines (content irrelevant for header-field validation). */
    private static List<PostJournalLineRequest> oneLine() {
        return List.of(new PostJournalLineRequest("ACCT-UID", null, null, null));
    }

    // -------------------------------------------------------------------------
    // companyUid — @NotBlank
    // -------------------------------------------------------------------------

    @Test
    void nullCompanyUid_producesViolation() {
        PostJournalRequest req = new PostJournalRequest(
                null, LocalDate.now(), "desc", null, null, oneLine());

        Set<ConstraintViolation<PostJournalRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("companyUid");
    }

    @Test
    void blankCompanyUid_producesViolation() {
        PostJournalRequest req = new PostJournalRequest(
                "   ", LocalDate.now(), "desc", null, null, oneLine());

        Set<ConstraintViolation<PostJournalRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("companyUid");
    }

    @Test
    void validCompanyUid_noCompanyUidViolation() {
        PostJournalRequest req = new PostJournalRequest(
                "01HZABCD1234567890ABCDEF12", LocalDate.now(), "desc", null, null, oneLine());

        Set<ConstraintViolation<PostJournalRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .doesNotContain("companyUid");
    }

    // -------------------------------------------------------------------------
    // postingDate — @NotNull
    // -------------------------------------------------------------------------

    @Test
    void nullPostingDate_producesViolation() {
        PostJournalRequest req = new PostJournalRequest(
                "01HZABCD1234567890ABCDEF12", null, "desc", null, null, oneLine());

        Set<ConstraintViolation<PostJournalRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("postingDate");
    }

    @Test
    void validPostingDate_noPostingDateViolation() {
        PostJournalRequest req = new PostJournalRequest(
                "01HZABCD1234567890ABCDEF12", LocalDate.now(), "desc", null, null, oneLine());

        Set<ConstraintViolation<PostJournalRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .doesNotContain("postingDate");
    }

    // -------------------------------------------------------------------------
    // Both missing → both violations reported
    // -------------------------------------------------------------------------

    @Test
    void bothMissing_bothViolationsReported() {
        PostJournalRequest req = new PostJournalRequest(
                null, null, "desc", null, null, oneLine());

        Set<ConstraintViolation<PostJournalRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("companyUid", "postingDate");
    }
}
