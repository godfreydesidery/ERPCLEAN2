package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.EmploymentStatus;
import com.erp.modules.hr.domain.enums.MaritalStatus;
import com.erp.modules.hr.domain.enums.PaymentMethod;
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
        Long userId,
        // Lifecycle + HR profile + org (P2 D6, ADR-0041)
        LocalDate terminationDate,
        String terminationReason,
        LocalDate confirmationDate,
        LocalDate probationEndDate,
        Long managerId,
        MaritalStatus maritalStatus,
        String nationality,
        Long positionId,
        // Contact fields (ADR-0040 D-11)
        String phone,
        String email,
        String addressLine,
        String region,
        String district,
        String postalAddress,
        // Payee fields — gate behind HR.EMPLOYEE.PAYEE.VIEW in the controller (ADR-0040 D-11)
        PaymentMethod paymentMethod,
        String bankName,
        String bankBranch,
        String bankAccountNo,
        String bankAccountName,
        String mobileMoneyNo
) {}
