package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.LeaveRequestStatus;
import jakarta.validation.constraints.NotNull;

public record DecideLeaveRequest(
        @NotNull LeaveRequestStatus decision,
        String decisionNote
) {}
