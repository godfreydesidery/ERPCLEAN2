package com.erp.platform.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Error-hygiene tests for the request-parameter branches (UAT finding #11).
 *
 * <p>The reported leak: a request without {@code companyId} came back as
 * {@code "Missing required request parameter: companyId"} — Spring's own text, naming an internal
 * wire identifier the user never typed and cannot act on. PROJECT-CONVENTIONS §3.1 requires
 * user-facing errors to be friendly with zero internal detail; the detail belongs in the log.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void missingRequestParameter_isFriendlyAndNamesNoParameter() {
        var ex = new MissingServletRequestParameterException("companyId", "Long");

        var response = handler.handleBadRequest(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorOf(response))
                .doesNotContain("companyId")
                .doesNotContain("parameter:")
                .doesNotContain("Long")
                .containsIgnoringCase("required information");
    }

    @Test
    void missingRequestParameter_stillAnswers400_notAnUnhandled500() {
        var response = handler.handleBadRequest(
                new MissingServletRequestParameterException("from", "LocalDate"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errors()).hasSize(1);
    }

    @Test
    void unreadableParameterValue_isFriendlyAndNamesNoParameter() throws Exception {
        var ex = new MethodArgumentTypeMismatchException(
                "not-a-date", java.time.LocalDate.class, "from", methodParameter(), null);

        var response = handler.handleBadRequest(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorOf(response))
                .doesNotContain("from")
                .doesNotContain("LocalDate")
                .containsIgnoringCase("could not be read");
    }

    private static String errorOf(org.springframework.http.ResponseEntity<ApiResponse<Void>> r) {
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().errors()).hasSize(1);
        return r.getBody().errors().get(0);
    }

    /** Any real method parameter will do — the handler only reads the exception's own fields. */
    private static MethodParameter methodParameter() throws NoSuchMethodException {
        return new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("sample", String.class), 0);
    }

    @SuppressWarnings("unused")
    private void sample(String from) {
        // signature holder for MethodParameter
    }
}
