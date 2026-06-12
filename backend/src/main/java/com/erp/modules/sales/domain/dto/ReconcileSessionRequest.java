package com.erp.modules.sales.domain.dto;

/**
 * Request to reconcile a closed POS session — posts the variance journal (ADR-0029 D-5).
 * The variance amount is computed server-side; no client input needed beyond the confirmation.
 */
public record ReconcileSessionRequest(
        String notes
) {}
