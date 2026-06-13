package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.PayrollRunStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PayrollRunDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String runNumber,
        short periodYear,
        short periodMonth,
        LocalDate payDate,
        PayrollRunStatus status,
        BigDecimal grossTotal,
        BigDecimal deductionTotal,
        BigDecimal netTotal,
        BigDecimal employerCostTotal,
        Instant calculatedAt,
        Instant approvedAt,
        Instant postedAt,
        Instant paidAt,
        Instant reversedAt,
        Long approvedBy,
        Long postedBy,
        String glEntryUid,
        String reversalOfRunUid
) {}
