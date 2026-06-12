package com.erp.modules.notifications.domain.dto;

/**
 * Unread in-app notification count for the shell badge (ADR-0024 D-10).
 */
public record UnreadCountDto(long count) {}
