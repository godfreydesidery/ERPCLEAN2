package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.CloseSessionRequest;
import com.erp.modules.sales.domain.dto.OpenSessionRequest;
import com.erp.modules.sales.domain.dto.PosPayoutRequest;
import com.erp.modules.sales.domain.dto.PosSessionDto;
import com.erp.modules.sales.domain.dto.ReconcileSessionRequest;
import com.erp.modules.sales.domain.dto.XReadDto;
import com.erp.modules.sales.domain.dto.ZReadDto;
import com.erp.modules.sales.domain.enums.PosSessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * POS session lifecycle (ADR-0029 D-5): open → payout* → close → reconcile.
 */
public interface PosSessionService {

    PosSessionDto openSession(OpenSessionRequest request);

    PosSessionDto getSessionByUid(String uid);

    /** List sessions for a company; when {@code status} is non-null, only that status is returned. */
    Page<PosSessionDto> listSessions(Long companyId, PosSessionStatus status, Pageable pageable);

    /** Record a cash-in or cash-out payout on an OPEN session. */
    void recordPayout(String sessionUid, PosPayoutRequest request);

    /** Close the session — cashier declares counted cash. */
    PosSessionDto closeSession(String sessionUid, CloseSessionRequest request);

    /** X-read: mid-session summary without closing (for manager spot-checks). */
    XReadDto xRead(String sessionUid);

    /** Reconcile a CLOSED session — post variance GL entry, mark RECONCILED, return Z-read. */
    ZReadDto reconcileSession(String sessionUid, ReconcileSessionRequest request);
}
