package com.erp.modules.sales.domain.dto;

import com.erp.platform.common.domain.MasterStatus;
import java.time.Instant;

/**
 * Response DTO for a POS till (ADR-0029 D-5).
 *
 * <p>{@code hasOpenSession} (busy-day-sim bugfix) tells the till picker whether a till is
 * currently occupied, so the cashier finds out BEFORE attempting to open a session — not via a
 * 409 after the fact. {@code true} when a {@code PosSession} in status OPEN exists for this till.
 *
 * <p>The {@code openSession*} fields describe WHO is holding it (orphaned-shift recovery,
 * 2026-08). "Occupied" alone is a dead end: a cashier whose app was force-closed cannot tell
 * their own abandoned shift from a colleague's live one. With the occupant's id and open time the
 * till app offers "resume/close your own shift" and otherwise explains that a supervisor must
 * close it. All five are {@code null} when no session is open.
 *
 * <p>{@code openSessionCashierName} names the occupant so a colleague's till reads "Asha has a
 * shift open" rather than "another cashier" — the id alone only ever answered "is this mine?".
 * When a session IS open but its cashier can no longer be named (account removed) this carries a
 * neutral phrase, never an id: clients print it as-is.
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
        boolean hasOpenSession,
        String openSessionUid,
        Long openSessionCashierId,
        String openSessionCashierName,
        Instant openSessionOpenedAt
) {}
