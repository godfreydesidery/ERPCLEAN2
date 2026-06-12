package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.EmploymentStatus;
import java.time.LocalDate;

public record EmployeeDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String employeeNumber,
        String firstName,
        String lastName,
        String nationalId,
        String tin,
        String nssfNumber,
        String heslbNumber,
        LocalDate dateOfBirth,
        String gender,
        LocalDate hireDate,
        Long departmentId,
        String departmentName,
        String jobTitle,
        EmploymentStatus status,
        Long userId
) {}
