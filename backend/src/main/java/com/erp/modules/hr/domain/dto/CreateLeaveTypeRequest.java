package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.LeaveAccrualMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateLeaveTypeRequest(
        @NotBlank @Size(max = 30)  String code,
        @NotBlank @Size(max = 120) String name,
        boolean paid,
        @NotNull @DecimalMin("0") BigDecimal annualEntitlementDays,
        @NotNull LeaveAccrualMethod accrualMethod,
        boolean carryForward,
        boolean requiresApproval,
        @Size(max = 10) String genderEligibility,
        Integer maxConsecutiveDays
) {}
