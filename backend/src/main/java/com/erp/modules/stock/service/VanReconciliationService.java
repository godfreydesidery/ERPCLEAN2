package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.dto.CreateVanReconciliationRequest;
import com.erp.modules.stock.domain.dto.EnterVanReconciliationLinesRequest;
import com.erp.modules.stock.domain.dto.VanReconciliationDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Van-stock reconciliation worksheet operations (ADR-0051 D-8).
 *
 * <p>RECORD-ONLY (D-8.2): none of these operations post a stock movement or GL journal. The van's
 * on-hand is not truthful (route sales issue from the branch warehouse, never the van — see the
 * ADR Context), so the worksheet is an accountability control, not a stock/valuation correction.
 */
public interface VanReconciliationService {

    /**
     * Create an OPEN worksheet for a van + business date: derives {@code loaded}/{@code returned}
     * per product from the transfer ledger (Σ TRANSFER_IN / −Σ TRANSFER_OUT at the van location in
     * the business-date window), snapshots the van's avg cost, and computes {@code expected}
     * (with {@code sold = 0} until entered).
     */
    VanReconciliationDto create(CreateVanReconciliationRequest request);

    /**
     * Enter the round's sold total + physical count for each line (optionally overriding the
     * derived loaded/returned). Recomputes expected/variance/variance-value. Only valid while OPEN.
     */
    VanReconciliationDto enterLines(String uid, EnterVanReconciliationLinesRequest request);

    /**
     * Mark the worksheet RECONCILED. RECORD-ONLY: posts no stock movement, no GL journal — emits an
     * informational {@code VAN.RECONCILED} outbox event only. Only valid while OPEN.
     */
    VanReconciliationDto reconcile(String uid);

    /** Cancel a worksheet (frees the van+date slot for a fresh worksheet). Not valid once RECONCILED. */
    VanReconciliationDto cancel(String uid);

    VanReconciliationDto getByUid(String uid);

    Page<VanReconciliationDto> list(Pageable pageable);
}
