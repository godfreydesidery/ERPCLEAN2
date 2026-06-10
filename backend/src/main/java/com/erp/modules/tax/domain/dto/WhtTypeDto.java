package com.erp.modules.tax.domain.dto;

import com.erp.modules.tax.domain.enums.WhtKind;
import java.math.BigDecimal;

/**
 * WHT type response DTO (ADR-0017 D-1 / D-2d).
 */
public record WhtTypeDto(
        Long id,
        String uid,
        Long companyId,
        String code,
        String name,
        WhtKind kind,
        BigDecimal ratePct,
        boolean active
) {}
