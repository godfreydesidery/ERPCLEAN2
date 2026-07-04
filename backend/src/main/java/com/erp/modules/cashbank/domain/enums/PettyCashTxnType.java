package com.erp.modules.cashbank.domain.enums;

/**
 * Petty-cash fund movement type (ADR-0050 D-7 PR-B).
 *
 * <p>{@code DISBURSEMENT} decrements the fund balance, {@code REPLENISHMENT} increments it, and
 * {@code ADJUSTMENT} applies a signed correction (positive increases, negative decreases).
 */
public enum PettyCashTxnType {
    DISBURSEMENT,
    REPLENISHMENT,
    ADJUSTMENT
}
