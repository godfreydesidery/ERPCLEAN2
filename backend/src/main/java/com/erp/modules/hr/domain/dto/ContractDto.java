package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.ContractType;
import com.erp.modules.hr.domain.enums.PayFrequency;
import java.math.BigDecimal;
import java.time.LocalDate;

public record ContractDto(
        Long id,
        String uid,
        Long companyId,
        Long employeeId,
        ContractType contractType,
        BigDecimal baseSalaryAmount,
        String currency,
        PayFrequency payFrequency,
        LocalDate startDate,
        LocalDate endDate,
        boolean payeResident,
        boolean nssfMember,
        boolean heslbBorrower,
        boolean wcfCovered,
        boolean sdlCounted,
        boolean active
) {}
