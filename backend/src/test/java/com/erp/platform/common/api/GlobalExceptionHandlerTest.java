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
    void discountRefusal_carriesItsCodeInAHeader_soNoClientMustMatchEnglishProse() {
        // UAT finding #13: the web invoice screen decided whether to offer a manager-approval
        // prompt by string-matching the server's English refusal text. One rewording and the button
        // silently disappears. The dedicated handler must win over the generic ConflictException
        // one and surface the code.
        var ex = new com.erp.modules.sales.domain.exception.DiscountApprovalException(
                com.erp.modules.sales.domain.enums.DiscountRefusalCode.DISCOUNT_APPROVAL_REQUIRED,
                "A manager needs to approve a discount this large.");

        var response = handler.handleDiscountApproval(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getFirst("X-Discount-Refusal"))
                .isEqualTo("DISCOUNT_APPROVAL_REQUIRED");
        // The friendly sentence is unchanged — the code is additive, not a replacement.
        assertThat(errorOf(response)).isEqualTo("A manager needs to approve a discount this large.");
    }

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

    // ---------------------------------------------------------------------------------------------
    // ACC-21: accounting-setup refusals — operators get a plain sentence, accountants the detail
    // ---------------------------------------------------------------------------------------------

    @Test
    void accountingSetup_onAnOperationalScreen_showsThePlainSentence_notGlJargon() {
        var ex = new AccountingSetupException(
                "No account is mapped for Stock Adjustment (STOCK_ADJUSTMENT) postings.");
        var request = new org.springframework.mock.web.MockHttpServletRequest(
                "POST", "/api/v1/stock/adjustments");

        var response = handler.handleAccountingSetup(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorOf(response))
                .isEqualTo(AccountingSetupException.DEFAULT_USER_MESSAGE)
                .doesNotContain("STOCK_ADJUSTMENT")
                .doesNotContainIgnoringCase("posting role");
    }

    @Test
    void accountingSetup_carriesASpecificOperatorSentence_whenTheThrowerGaveOne() {
        var ex = new AccountingSetupException(
                "The fiscal period covering 2027-01-02 is closed.",
                "The accounting period for 2027-01-02 is closed. Ask your accountant.");
        var request = new org.springframework.mock.web.MockHttpServletRequest(
                "POST", "/api/v1/ar/receipts");

        assertThat(errorOf(handler.handleAccountingSetup(ex, request)))
                .isEqualTo("The accounting period for 2027-01-02 is closed. Ask your accountant.");
    }

    @Test
    void accountingSetup_onTheGlScreens_keepsTheAccountantMessage() {
        var ex = new AccountingSetupException(
                "No account is mapped for Retained Earnings (RETAINED_EARNINGS) postings.");
        for (String path : java.util.List.of(
                "/api/v1/gl/journals", "/api/v1/gl/periods/fiscal-years/uid/X/close",
                "/api/v1/vat/returns/uid/X/file", "/api/v1/fixed-assets/depreciation-runs",
                "/api/v1/fx/revaluation-runs", "/api/v1/hr/payroll-runs/uid/X/finalise")) {
            var request = new org.springframework.mock.web.MockHttpServletRequest("POST", path);
            assertThat(errorOf(handler.handleAccountingSetup(ex, request)))
                    .as(path)
                    .contains("Retained Earnings");
        }
    }

    @Test
    void accountingSetup_isStillAnIllegalStateException_soExistingCatchesKeepWorking() {
        assertThat(new AccountingSetupException("x")).isInstanceOf(IllegalStateException.class);
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
