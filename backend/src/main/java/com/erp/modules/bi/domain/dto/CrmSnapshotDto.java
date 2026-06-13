package com.erp.modules.bi.domain.dto;

import com.erp.modules.crm.domain.dto.CrmKpiDto;
import com.erp.modules.crm.domain.dto.ForecastDto;
import com.erp.modules.crm.domain.dto.PipelineSummaryDto;

/**
 * CRM panel: pipeline by stage + KPIs + forecast (ADR-0037 D-6 C-1/C-2/C-3).
 * Reuses CRM DTOs directly — PipelineQuery.pipeline + kpis + forecast.
 */
public record CrmSnapshotDto(
        PipelineSummaryDto pipeline,
        CrmKpiDto          kpis,
        ForecastDto        forecast
) {}
