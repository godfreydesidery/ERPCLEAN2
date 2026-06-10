package com.erp.modules.reporting.domain.dto;

import com.erp.modules.reporting.domain.enums.StatementSection;
import java.util.List;

/**
 * A named section of a financial statement with its detail lines and subtotal (ADR-0018 D-1).
 */
public record StatementSectionDto(
        StatementSection    sectionKey,
        String              title,
        List<StatementLineDto> lines,
        AmountPairDto       subtotal
) {}
