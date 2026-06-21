package com.erp.modules.sales.service;

import java.math.BigDecimal;

/**
 * Validates discount inputs for sales documents (ADR-0021 D-9, FIX-3).
 *
 * <p>Rules:
 * <ul>
 *   <li>Discount amount must be >= 0 when provided.</li>
 *   <li>Discount percent must be >= 0 and <= 100 when provided.</li>
 * </ul>
 * Negative discounts are silently dropped by the totals calculator, so they must be
 * rejected at the boundary to prevent silent mis-computation and misleading stored data.
 */
final class DiscountValidator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private DiscountValidator() {}

    /**
     * Validates a line-level discount pair.
     *
     * <p>Rules:
     * <ul>
     *   <li>Amount and percent are mutually exclusive — both non-zero is ambiguous (SALES-BILLING).</li>
     *   <li>Amount must be >= 0 when provided.</li>
     *   <li>Percent must be in [0, 100] when provided.</li>
     * </ul>
     *
     * @param amount  line discount amount (nullable)
     * @param percent line discount percent (nullable)
     * @throws IllegalArgumentException if any value is out of range or both are provided
     */
    static void validateLineDiscount(BigDecimal amount, BigDecimal percent) {
        boolean hasAmount  = amount  != null && amount.compareTo(BigDecimal.ZERO) != 0;
        boolean hasPercent = percent != null && percent.compareTo(BigDecimal.ZERO) != 0;
        if (hasAmount && hasPercent) {
            throw new IllegalArgumentException(
                    "lineDiscountAmount and lineDiscountPercent are mutually exclusive — "
                            + "supply one or the other, not both (SALES-BILLING).");
        }
        if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(
                    "Line discount amount cannot be negative; got: " + amount.toPlainString());
        }
        validatePercent(percent, "Line discount percent");
    }

    /**
     * Validates a document (header) level discount pair.
     *
     * @param amount  doc discount amount (nullable)
     * @param percent doc discount percent (nullable)
     * @throws IllegalArgumentException if any value is out of range
     */
    static void validateDocDiscount(BigDecimal amount, BigDecimal percent) {
        if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(
                    "Document discount amount cannot be negative; got: " + amount.toPlainString());
        }
        validatePercent(percent, "Document discount percent");
    }

    private static void validatePercent(BigDecimal percent, String label) {
        if (percent == null) return;
        if (percent.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(
                    label + " cannot be negative; got: " + percent.toPlainString());
        }
        if (percent.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException(
                    label + " cannot exceed 100; got: " + percent.toPlainString());
        }
    }
}
