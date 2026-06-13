package com.erp.modules.notifications.domain.dto;

/**
 * Admin request to enable or disable a notification type per company (ADR-0024 D-10).
 */
public record SetCompanyTypeStateRequest(boolean enabled) {}
