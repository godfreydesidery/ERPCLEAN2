package com.erp.modules.reporting.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * Derives the default comparative window per statement type (ADR-0018 D-8, OQ-REP-03).
 *
 * <ul>
 *   <li>P&amp;L / Cash-Flow (flows): prior period of equal length — [fromDate−len, fromDate−1].
 *   <li>Balance Sheet (stock): as-at fromDate − 1 (the opening position / prior period start).
 * </ul>
 *
 * Callers may override by passing explicit compare dates; this resolver supplies the default only.
 */
@Component
public class ComparativeWindowResolver {

    /**
     * Default comparative fromDate for a P&amp;L / CF period of length
     * {@code [primaryFrom, primaryTo]}: immediately prior period of the same length.
     */
    public LocalDate comparativeFrom(LocalDate primaryFrom, LocalDate primaryTo) {
        long len = ChronoUnit.DAYS.between(primaryFrom, primaryTo) + 1;
        return primaryFrom.minusDays(len);
    }

    /**
     * Default comparative toDate for a P&amp;L / CF period: the day before primaryFrom.
     */
    public LocalDate comparativeTo(LocalDate primaryFrom) {
        return primaryFrom.minusDays(1);
    }

    /**
     * Default comparative as-at date for a Balance Sheet: the day before the primary fromDate
     * (i.e. the opening position / prior period-end).
     */
    public LocalDate comparativeAsAt(LocalDate primaryFrom) {
        return primaryFrom.minusDays(1);
    }
}
