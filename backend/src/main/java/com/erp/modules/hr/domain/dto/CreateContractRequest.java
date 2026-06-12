package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.ContractType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateContractRequest(
        @NotNull ContractType contractType,
        @NotNull @Positive BigDecimal baseSalaryAmount,
        @NotNull LocalDate startDate,
        LocalDate endDate,
        boolean payeResident,
        boolean nssfMember,
        boolean heslbBorrower,
        boolean wcfCovered,
        boolean sdlCounted
) {}
