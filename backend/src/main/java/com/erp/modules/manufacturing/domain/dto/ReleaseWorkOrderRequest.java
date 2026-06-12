package com.erp.modules.manufacturing.domain.dto;

/** Request body for POST /api/v1/work-orders/uid/{uid}/release (ADR-0035 D-2). */
public record ReleaseWorkOrderRequest(
        /** Optional: override/pin a specific BOM version uid. Null → use ACTIVE BOM. */
        String bomUid
) {}
