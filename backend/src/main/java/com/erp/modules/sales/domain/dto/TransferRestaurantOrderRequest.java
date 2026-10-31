package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Moves an order to another waiter (ADR-0067 D-2).
 *
 * @param toWaiterUid     the waiter taking the table over
 * @param actingWaiterUid the waiter doing it on a shared till, when one is signed in there — a
 *                        waiter needs a manager's approval even on a cashier's till
 * @param authorisedByUid the manager who approved it at the till (step-up), re-verified
 */
public record TransferRestaurantOrderRequest(
        @NotBlank String toWaiterUid,
        String actingWaiterUid,
        String authorisedByUid) {}
