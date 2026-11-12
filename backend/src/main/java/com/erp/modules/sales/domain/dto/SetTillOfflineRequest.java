package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotNull;

/** Allow or stop offline selling on one till (ADR-0068 D-1). */
public record SetTillOfflineRequest(@NotNull Boolean offlineAllowed) {}
