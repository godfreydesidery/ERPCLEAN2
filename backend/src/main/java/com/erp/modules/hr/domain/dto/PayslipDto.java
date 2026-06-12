package com.erp.modules.hr.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PayslipDto(
        Long id,
        String uid,
        Long companyId,
        Long payrollRunId,
        Long payrollLineId,
        Long employeeId,
        String payslipNumber,
        LocalDate payDate,
        BigDecimal grossAmount,
        BigDecimal deductionAmount,
        BigDecimal netAmount,
        BigDecimal employerCostAmount,
        BigDecimal ytdGross,
        BigDecimal ytdPaye,
        BigDecimal ytdNssfEmployee,
        BigDecimal ytdNet
) {}
