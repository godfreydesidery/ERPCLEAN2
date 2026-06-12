package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.PurchaseRequisition;
import com.erp.modules.purchases.domain.enums.RequisitionStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PurchaseRequisitionDto(
        Long   id,
        String uid,
        Long   companyId,
        Long   branchId,
        String requisitionNumber,
        RequisitionStatus status,
        LocalDate requiredByDate,
        String costCentreCode,
        String approvalRequestUid,
        String approvalStatus,
        String convertedToType,
        String convertedToUid,
        String notes,
        Instant submittedAt,
        Instant approvedAt,
        Instant rejectedAt,
        Instant convertedAt,
        Instant cancelledAt,
        Instant createdAt,
        List<PurchaseRequisitionLineDto> lines
) {
    public static PurchaseRequisitionDto from(PurchaseRequisition r, List<PurchaseRequisitionLineDto> lines) {
        return new PurchaseRequisitionDto(
                r.getId(), r.getUid(),
                r.getCompanyId(), r.getBranchId(),
                r.getRequisitionNumber(), r.getStatus(),
                r.getRequiredByDate(), r.getCostCentreCode(),
                r.getApprovalRequestUid(), r.getApprovalStatus(),
                r.getConvertedToType(), r.getConvertedToUid(),
                r.getNotes(),
                r.getSubmittedAt(), r.getApprovedAt(), r.getRejectedAt(),
                r.getConvertedAt(), r.getCancelledAt(), r.getCreatedAt(),
                lines);
    }
}
