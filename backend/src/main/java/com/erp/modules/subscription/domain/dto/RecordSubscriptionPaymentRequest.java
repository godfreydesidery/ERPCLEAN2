package com.erp.modules.subscription.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A payment the operator records; {@code paidUntil} becomes the subscription's (ADR-0069 D-8). */
public record RecordSubscriptionPaymentRequest(
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currencyCode,
        @NotNull LocalDate paidOn,
        @NotNull LocalDate paidUntil,
        @Size(max = 100) String reference,
        @Size(max = 500) String note) {
}
