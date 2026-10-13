package com.erp.platform.common.money;

import java.math.BigDecimal;

/**
 * Wire representation of a {@link Money} pair per ADR-0005 D-7:
 * {@code { "amount": "1500.0000", "currency": "TZS" }}.
 * {@code amount} is a String to avoid IEEE-754 precision loss in JavaScript.
 * Null when no money value is set (e.g. no credit limit on a walk-in customer).
 *
 * <p>Promoted from {@code com.erp.modules.parties.domain.dto} to
 * {@code com.erp.platform.common.money} (ADR-0007 D-12) so every money-bearing module
 * shares one wire DTO without a cross-module import.
 */
public record MoneyDto(String amount, String currency) {

    public static MoneyDto from(Money money) {
        if (money == null || !money.isPresent()) {
            return null;
        }
        return new MoneyDto(money.getAmount().toPlainString(), money.getCurrency().value());
    }

    public static Money toMoney(MoneyDto dto) {
        if (dto == null || dto.amount() == null || dto.currency() == null) {
            return null;
        }
        return new Money(parseAmount(dto.amount()), dto.currency());
    }

    /**
     * Parses a wire amount, refusing anything that is not a plain decimal with a friendly sentence
     * (LUI-04). {@code new BigDecimal("1,800")} throws a NumberFormatException whose JDK text used
     * to reach the user verbatim. Separators are rejected, not guessed at: "1,800" could be one
     * thousand eight hundred or one point eight depending on the typist's locale.
     */
    static BigDecimal parseAmount(String raw) {
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "An amount is not a valid number. Enter digits only, without commas or spaces "
                    + "(for example 1800.50).");
        }
    }
}
