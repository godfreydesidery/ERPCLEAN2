package com.erp.modules.sales.domain.dto;

import com.erp.platform.common.domain.MasterStatus;

/**
 * Response DTO for a POS till (ADR-0029 D-5).
 *
 * <p>{@code hasOpenSession} (busy-day-sim bugfix) tells the till picker whether a till is
 * currently occupied, so the cashier finds out BEFORE attempting to open a session — not via a
 * 409 after the fact. {@code true} when a {@code PosSession} in status OPEN exists for this till.
 */
public record PosTillDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String code,
        String name,
        Long cashBankAccountId,
        MasterStatus status,
        boolean hasOpenSession
) {}
