package com.erp.modules.hr.domain.dto;

import java.math.BigDecimal;

public record LeaveBalanceDto(
        Long id,
        String uid,
        Long employeeId,
        Long leaveTypeId,
        String leaveTypeName,
        short asOfYear,
        BigDecimal entitledDays,
        BigDecimal takenDays,
        BigDecimal balanceDays
) {}
