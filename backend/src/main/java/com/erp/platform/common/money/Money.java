package com.erp.platform.common.money;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import lombok.Getter;

/**
 * Inseparable monetary value: an {@code (amount, currency)} pair (ADR-0005 D-1).
 *
 * <p>Rules enforced by this embeddable:
 * <ul>
 *   <li>Both fields are null together, or both non-null together — mixed-null is rejected at
 *       the type level (the DB CHECK constraint mirrors this at the storage level).</li>
 *   <li>{@code amount} is {@link BigDecimal}; never {@code float}/{@code double}.</li>
 *   <li>{@code currency} is an ISO 4217 alpha-3 code ({@code CHAR(3)}) stored as a
 *       {@code String}; validated non-blank when amount is set.</li>
 * </ul>
 *
 * <p>Map into an entity with {@code @Embedded} and {@code @AttributeOverride}s to the
 * column-pair naming convention {@code <field>_amount} / {@code <field>_currency}. Example:
 * <pre>{@code
 * @Embedded
 * @AttributeOverrides({
 *     @AttributeOverride(name = "amount",   column = @Column(name = "credit_limit_amount")),
 *     @AttributeOverride(name = "currency", column = @Column(name = "credit_limit_currency", length = 3))
 * })
 * private Money creditLimit;
 * }</pre>
 *
 * <p>Arithmetic (plus/minus/compareTo) is intentionally omitted from this initial embeddable —
 * the FX engine that makes them safe is deferred (ADR-0005 D-8). When it lands, arithmetic
 * helpers will be added here under their own ADR. For now {@code Money} is a value-holder only.
 *
 * <p>Equality: {@code amount} compared by value ({@link BigDecimal#compareTo}), {@code currency}
 * by exact string match — consistent with ADR-0005 D-6.
 */
@Getter
@Embeddable
public class Money {

    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", length = 3)
    private String currency;

    /** JPA no-arg constructor. */
    protected Money() {
    }

    /**
     * Full constructor — both fields must be non-null (ADR-0005 D-1: a monetary value is always
     * an {@code (amount, currency)} pair).
     *
     * @param amount   the amount (non-null, {@link BigDecimal})
     * @param currency ISO 4217 alpha-3 code, non-blank (e.g. {@code "TZS"}, {@code "USD"})
     * @throws IllegalArgumentException if either argument is null / blank
     */
    public Money(BigDecimal amount, String currency) {
        if (amount == null) {
            throw new IllegalArgumentException("Money amount must not be null");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Money currency must not be null or blank");
        }
        this.amount = amount;
        this.currency = currency;
    }

    /**
     * Returns a null {@link Money} to embed — i.e., both columns will be written as SQL NULL.
     * Use this when no credit limit is set, rather than setting the field to {@code null} directly
     * (keeps callers explicit about intent).
     */
    public static Money none() {
        return null; // deliberate — both columns remain null in the row
    }

    /** True when both {@code amount} and {@code currency} are present. */
    public boolean isPresent() {
        return amount != null && currency != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money other)) return false;
        if (amount == null && other.amount == null && currency == null && other.currency == null) {
            return true;
        }
        if (amount == null || other.amount == null || currency == null || other.currency == null) {
            return false;
        }
        return amount.compareTo(other.amount) == 0 && currency.equals(other.currency);
    }

    @Override
    public int hashCode() {
        if (amount == null) return 0;
        return 31 * amount.stripTrailingZeros().hashCode() + (currency != null ? currency.hashCode() : 0);
    }

    @Override
    public String toString() {
        return amount + " " + currency;
    }
}
