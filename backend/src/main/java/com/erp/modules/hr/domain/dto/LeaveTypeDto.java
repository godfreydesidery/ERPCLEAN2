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
        boolean active
) {}
