package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.LeaveAccrualMethod;
import java.math.BigDecimal;

public record LeaveTypeDto(
        Long id,
        String uid,
        Long companyId,
        String code,
        String name,
        boolean paid,
        BigDecimal annualEntitlementDays,
        LeaveAccrualMethod accrualMethod,
        boolean active,
        // Leave policy fields (P2 D6, ADR-0041)
        boolean carryForward,
        boolean requiresApproval,
        String genderEligibility,
        Integer maxConsecutiveDays
) {}
