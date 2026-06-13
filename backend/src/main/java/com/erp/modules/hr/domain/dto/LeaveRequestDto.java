package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.LeaveRequestStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record LeaveRequestDto(
        Long id,
        String uid,
        Long companyId,
        Long employeeId,
        String employeeName,
        Long leaveTypeId,
        String leaveTypeName,
        LocalDate fromDate,
        LocalDate toDate,
        BigDecimal days,
        LeaveRequestStatus status,
        Long decidedBy,
        Instant decidedAt,
        String reason,
        String decisionNote
) {}
