package com.erp.modules.iam.domain.dto;

import java.time.Instant;

/**
 * One row of the platform console's tenant list (MULTITENANCY-PLAN §14.2 R-2).
 *
 * <p>Deliberately thin: who the tenant is, whether it can sign in, and how big it is. Nothing a
 * tenant would consider private (no user names, no figures) — the platform operator administers
 * tenants, it does not read their books.
 */
public record TenantSummaryDto(
        String uid,
        String name,
        String alias,
        String status,
        Instant createdAt,
        long companyCount,
        long userCount,
        /**
         * True for the organisation the caller is working in right now: its home, or the tenant it
         * has entered. The console offers neither Enter nor Suspend on that row (the server refuses
         * suspending it anyway).
         */
        boolean current) {
}
