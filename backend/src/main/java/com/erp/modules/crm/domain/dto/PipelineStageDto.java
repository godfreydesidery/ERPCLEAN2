package com.erp.modules.crm.domain.dto;

import com.erp.modules.crm.domain.entity.PipelineStage;
import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record PipelineStageDto(
        Long id,
        String uid,
        Long companyId,
        String name,
        short displayOrder,
        BigDecimal defaultProbability,
        boolean active,
        MasterStatus status,
        Long version,
        Instant createdAt,
        Long createdBy,
        Instant updatedAt,
        Long updatedBy
) {
    public static PipelineStageDto from(PipelineStage s) {
        return new PipelineStageDto(
                s.getId(), s.getUid(), s.getCompanyId(),
                s.getName(), s.getDisplayOrder(), s.getDefaultProbability(),
                s.isActive(), s.getStatus(), s.getVersion(),
                s.getCreatedAt(), s.getCreatedBy(), s.getUpdatedAt(), s.getUpdatedBy());
    }
}
