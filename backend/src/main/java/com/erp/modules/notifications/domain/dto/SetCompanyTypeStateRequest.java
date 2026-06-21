package com.erp.modules.notifications.domain.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Admin request to enable or disable a notification type per company (ADR-0024 D-10).
 * Uses boxed {@code Boolean} so a missing/empty body produces {@code null} and is
 * distinguishable from an explicit {@code false} (defect 2 guard in the service).
 */
public record SetCompanyTypeStateRequest(@NotNull Boolean enabled) {}
