package com.erp.modules.cashbank.domain.dto;

/**
 * Narrow picker row for a countable till: an ACTIVE, CASH-type cash/bank account (LRB-03 / ADM-01).
 *
 * <p>Deliberately carries no balances, bank details or GL link. It exists so a cashier who holds
 * only the cash-count permissions can choose the drawer they are counting without being granted
 * {@code CASH.VIEW}, which would expose every bank account and its figures.
 */
public record CashTillOptionDto(
        Long id,
        String uid,
        String code,
        String name,
        Long branchId,
        String currency
) {}
