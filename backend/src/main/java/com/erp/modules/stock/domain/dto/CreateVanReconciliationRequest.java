package com.erp.modules.stock.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * Request DTO: create a van reconciliation worksheet (ADR-0051 D-8).
 * loaded/returned per product are derived from the transfer ledger; sold=0 until entered.
 */
public record CreateVanReconciliationRequest(
        @NotBlank String vanLocationUid,
        @NotNull LocalDate businessDate,
        @Size(max = 500) String notes,
        /** Optional product uid subset. Empty/null = every product touched by a van transfer in the window. */
        @Size(max = 500) List<String> productUids
) {}
