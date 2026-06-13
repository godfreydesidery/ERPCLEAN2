package com.erp.modules.hr.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

public record SubmitLeaveRequest(
        @NotNull Long leaveTypeId,
        @NotNull LocalDate fromDate,
        @NotNull LocalDate toDate,
        @NotNull @Positive BigDecimal days,
        String reason
) {}
