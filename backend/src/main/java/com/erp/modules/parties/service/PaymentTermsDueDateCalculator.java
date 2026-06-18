package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.entity.PaymentTerms;
import com.erp.modules.parties.domain.enums.PaymentTermsBasis;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Reusable derivation of a due date (and settlement-discount deadline / amount) from a
 * {@link PaymentTerms} master (ADR-0041 D1). Pure / stateless — no Spring, no IO.
 *
 * <p>Due-date basis (ADR-0040 D-2):
 * <ul>
 *   <li>{@code DAYS_AFTER_INVOICE}  → docDate + net_days</li>
 *   <li>{@code DAYS_AFTER_MONTH_END} → end-of-month(docDate) + net_days</li>
 *   <li>{@code DUE_ON_RECEIPT}      → docDate (net_days ignored)</li>
 * </ul>
 * When {@code terms} is null the document falls back to net-on-receipt (docDate).
 *
 * <p>Settlement discount (early-payment) is data-only in v1: the deadline is
 * {@code docDate + discount_days} and the amount is {@code grossAmount × discount_percent / 100}.
 * No GL discount leg is posted (ADR-0041 D1 FORK).
 */
public final class PaymentTermsDueDateCalculator {

    private PaymentTermsDueDateCalculator() {
        // utility
    }

    /**
     * Derives the due date for a document raised on {@code docDate} under {@code terms}.
     * Falls back to {@code docDate} (net-on-receipt) when {@code terms} is null.
     */
    public static LocalDate derive(LocalDate docDate, PaymentTerms terms) {
        if (docDate == null) {
            return null;
        }
        if (terms == null) {
            return docDate;
        }
        PaymentTermsBasis basis = terms.getBasis();
        int netDays = Math.max(terms.getNetDays(), 0);
        return switch (basis) {
            case DAYS_AFTER_INVOICE   -> docDate.plusDays(netDays);
            case DAYS_AFTER_MONTH_END -> docDate.withDayOfMonth(docDate.lengthOfMonth())
                                                .plusDays(netDays);
            case DUE_ON_RECEIPT       -> docDate;
        };
    }

    /**
     * Derives the due date using an integer net-days fallback when no PaymentTerms master is set.
     * Priority: {@code terms} (master) &gt; {@code netDaysFallback} (integer) &gt; docDate.
     */
    public static LocalDate derive(LocalDate docDate, PaymentTerms terms, Integer netDaysFallback) {
        if (docDate == null) {
            return null;
        }
        if (terms != null) {
            return derive(docDate, terms);
        }
        if (netDaysFallback != null) {
            return docDate.plusDays(Math.max(netDaysFallback, 0));
        }
        return docDate;
    }

    /**
     * Settlement-discount deadline = {@code docDate + discount_days}, or null when the terms carry
     * no early-payment discount (discount_days null).
     */
    public static LocalDate settlementDiscountDueDate(LocalDate docDate, PaymentTerms terms) {
        if (docDate == null || terms == null || terms.getDiscountDays() == null) {
            return null;
        }
        return docDate.plusDays(Math.max(terms.getDiscountDays(), 0));
    }

    /**
     * Settlement-discount amount = {@code grossAmount × discount_percent / 100} (HALF_UP, scale 4),
     * or null when the terms carry no early-payment discount, or the gross amount is null/zero.
     * Data-only — no GL leg (ADR-0041 D1).
     */
    public static BigDecimal settlementDiscountAmount(BigDecimal grossAmount, PaymentTerms terms) {
        if (grossAmount == null
                || terms == null
                || terms.getDiscountPercent() == null
                || terms.getDiscountPercent().compareTo(BigDecimal.ZERO) <= 0
                || grossAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return grossAmount.multiply(terms.getDiscountPercent())
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }
}
