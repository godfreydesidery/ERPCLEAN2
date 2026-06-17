package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.PaymentMethod;
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
        Long userId,
        // Contact fields (ADR-0040 D-11)
        String phone,
        String email,
        String addressLine,
        String region,
        String district,
        String postalAddress,
        // Payee / disbursement fields (ADR-0040 D-11)
        PaymentMethod paymentMethod,
        String bankName,
        String bankBranch,
        String bankAccountNo,
        String bankAccountName,
        String mobileMoneyNo
) {}
