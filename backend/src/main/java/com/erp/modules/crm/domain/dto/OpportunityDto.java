package com.erp.modules.crm.domain.dto;

import com.erp.modules.crm.domain.entity.Opportunity;
import com.erp.modules.crm.domain.enums.OpportunityLossReason;
import com.erp.modules.crm.domain.enums.OpportunityStatus;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record OpportunityDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String opportunityNumber,
        OpportunityStatus opportunityStatus,
        String title,
        Long customerId,
        String customerUid,
        Long agentId,
        Long ownerUserId,
        Long sourceLeadId,
        String sourceLeadUid,
        Long pipelineStageId,
        BigDecimal winProbability,
        BigDecimal estimatedValueAmount,
        String currency,
        LocalDate expectedCloseDate,
        Instant wonAt,
        Instant lostAt,
        OpportunityLossReason lossReason,
        String nextStep,
        LocalDate nextActionDate,
        Instant stageChangedAt,
        String convertedDocumentKind,
        String convertedDocumentUid,
        Instant convertedAt,
        MasterStatus status,
        Long version,
        Instant createdAt,
        Long createdBy,
        Instant updatedAt,
        Long updatedBy,
        List<OpportunityLineDto> lines
) {
    public static OpportunityDto from(Opportunity o, List<OpportunityLineDto> lines) {
        return new OpportunityDto(
                o.getId(), o.getUid(), o.getCompanyId(), o.getBranchId(),
                o.getOpportunityNumber(), o.getOpportunityStatus(), o.getTitle(),
                o.getCustomerId(), o.getCustomerUid(), o.getAgentId(), o.getOwnerUserId(),
                o.getSourceLeadId(), o.getSourceLeadUid(), o.getPipelineStageId(),
                o.getWinProbability(), o.getEstimatedValueAmount(), CurrencyCode.value(o.getCurrency()),
                o.getExpectedCloseDate(), o.getWonAt(), o.getLostAt(), o.getLossReason(),
                o.getNextStep(), o.getNextActionDate(), o.getStageChangedAt(),
                o.getConvertedDocumentKind(), o.getConvertedDocumentUid(), o.getConvertedAt(),
                o.getStatus(), o.getVersion(), o.getCreatedAt(), o.getCreatedBy(),
                o.getUpdatedAt(), o.getUpdatedBy(), lines);
    }
}
