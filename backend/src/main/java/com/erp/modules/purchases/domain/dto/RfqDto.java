package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.Rfq;
import com.erp.modules.purchases.domain.enums.RfqStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record RfqDto(
        Long   id,
        String uid,
        Long   companyId,
        Long   branchId,
        String rfqNumber,
        RfqStatus status,
        String sourceRequisitionUid,
        LocalDate responseDueDate,
        String awardedQuoteUid,
        String awardedPoUid,
        String awardReason,
        String notes,
        Instant sentAt,
        Instant awardedAt,
        Instant cancelledAt,
        Instant createdAt,
        List<RfqLineDto> lines,
        List<String> supplierUids
) {
    public static RfqDto from(Rfq r, List<RfqLineDto> lines, List<String> supplierUids) {
        return new RfqDto(
                r.getId(), r.getUid(),
                r.getCompanyId(), r.getBranchId(),
                r.getRfqNumber(), r.getStatus(),
                r.getSourceRequisitionUid(), r.getResponseDueDate(),
                r.getAwardedQuoteUid(), r.getAwardedPoUid(),
                r.getAwardReason(),
                r.getNotes(),
                r.getSentAt(), r.getAwardedAt(), r.getCancelledAt(), r.getCreatedAt(),
                lines, supplierUids);
    }
}
