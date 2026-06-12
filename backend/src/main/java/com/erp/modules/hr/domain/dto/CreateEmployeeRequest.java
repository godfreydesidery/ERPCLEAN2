package com.erp.modules.hr.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CreateEmployeeRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        String nationalId,
        String tin,
        String nssfNumber,
        String heslbNumber,
        LocalDate dateOfBirth,
        String gender,
        @NotNull LocalDate hireDate,
        Long departmentId,
        String jobTitle,
        Long branchId,
        Long userId
) {}
