package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * One line of the till-expense report's category roll-up (K8) — what a shop actually spent its
 * drawer cash on, bucketed. Ordered biggest-spend first so the line worth questioning is at the top.
 *
 * @param category the bucket the till operator picked
 * @param amount   total paid out under that bucket over the reported period (always positive)
 * @param count    how many individual expenses make up {@code amount}
 */
public record TillExpenseCategoryTotalDto(
        String category,
        BigDecimal amount,
        long count
) {}
