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
        BigDecimal balanceDays,
        // Accrual / carry-forward breakdown (P2 D6, ADR-0041)
        BigDecimal carriedForwardDays,
        BigDecimal accruedDays,
        BigDecimal pendingDays,
        BigDecimal adjustmentDays
) {}
