package com.erp.modules.projects.domain.dto;

import java.math.BigDecimal;

/**
 * One row in the cross-project WIP report (ADR-0033 D-6, FR-PROJ-09).
 * WIP = max(0, costIncurred − billed). Unbooked in v1 (BR-PROJ-07).
 */
public record ProjectWipRowDto(
        String projectUid,
        String projectNumber,
        String name,
        BigDecimal costIncurred,
        BigDecimal billed,
        BigDecimal wip,
        String currency
) {}
