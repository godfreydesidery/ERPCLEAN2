package com.erp.modules.crm.domain.dto;

import java.util.List;

/** Full pipeline view — open opportunities grouped by stage (ADR-0031 D-9). */
public record PipelineSummaryDto(
        List<PipelineStageSummaryRowDto> stages
) {}
