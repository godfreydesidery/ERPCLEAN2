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
    void missingFxRate_isAFriendly409_notAGeneric500() {
        // AP-20: a USD bill whose day rate was missing surfaced as "match failed" (500).
        var ex = new com.erp.platform.common.money.FxRateNotFoundException(
                7L, "USD", "TZS", java.time.LocalDate.of(2026, 10, 10));

        var response = handler.handleFxRateNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorOf(response))
                .contains("USD to TZS on 2026-10-10")
                .contains("exchange-rate screen")
                .doesNotContain("7");
    }

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

    @Test
    void numberWithThousandsSeparator_isFriendly_neverTheJdkText() {
        // LUI-04: new BigDecimal("1,800") — the JDK text used to reach the user verbatim.
        NumberFormatException nfe;
        try {
            new java.math.BigDecimal("1,800");
            throw new AssertionError("expected a NumberFormatException");
        } catch (NumberFormatException e) {
            nfe = e;
        }

        var response = handler.handleNumberFormat(nfe);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorOf(response))
                .doesNotContain("Character")
                .doesNotContain("decimal digit")
                .contains("without commas");
    }

    @Test
    void unknownEnumConstant_namesNoInternalClass() {
        // LRB-14: StockCountType.valueOf("PARTIAL") in a service.
        var ex = new IllegalArgumentException(
                "No enum constant com.erp.modules.stock.domain.enums.StockCountType.PARTIAL");

        var response = handler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorOf(response))
                .doesNotContain("com.erp")
                .doesNotContain("enum")
                .doesNotContain("StockCountType");
    }

    @Test
    void serviceSentence_isStillPassedThrough() {
        var response = handler.handleIllegalArgument(
                new IllegalArgumentException("Select a van location."));

        assertThat(errorOf(response)).isEqualTo("Select a van location.");
    }

    @Test
    void messageLessIllegalArgument_neverPutsANullInErrors() {
        var response = handler.handleIllegalArgument(new IllegalArgumentException());

        assertThat(errorOf(response)).isNotBlank();
    }

    @Test
    void commaAmountInAJsonNumberField_namesTheFieldNotTheType() {
        var ife = com.fasterxml.jackson.databind.exc.InvalidFormatException.from(
                null, "Cannot deserialize value of type `java.math.BigDecimal`", "1,800",
                java.math.BigDecimal.class);
        ife.prependPath(new com.fasterxml.jackson.databind.JsonMappingException.Reference(
                null, "unitCost"));
        var ex = new org.springframework.http.converter.HttpMessageNotReadableException(
                "JSON parse error", ife,
                new org.springframework.mock.http.MockHttpInputMessage(new byte[0]));

        var response = handler.handleMessageNotReadable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorOf(response))
                .contains("unitCost")
                .contains("without commas")
                .doesNotContain("BigDecimal");
    }

    @Test
    void moneyDto_refusesACommaAmountWithAFriendlySentence() {
        var dto = new com.erp.platform.common.money.MoneyDto("1,800", "TZS");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> com.erp.platform.common.money.MoneyDto.toMoney(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(NumberFormatException.class)
                .hasMessageContaining("without commas");
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
