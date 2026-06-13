package com.erp.modules.bi.domain.dto;

import java.math.BigDecimal;

/**
 * Recon health badge — one per reconciliation tie (AR/AP/Cash/Stock/TB) (ADR-0037 D-6).
 * Rendered as [OK] / [!] text-prefixed badge on the frontend (a11y: non-color cue).
 */
public record HealthIndicatorDto(
        String     label,
        boolean    ties,
        BigDecimal difference
) {}
