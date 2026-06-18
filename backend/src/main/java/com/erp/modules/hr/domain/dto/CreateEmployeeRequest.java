package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.PaymentMethod;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

public record CreateEmployeeRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        String nationalId,
        String tin,
        String nssfNumber,
        String heslbNumber,
        LocalDate dateOfBirth,
        @Pattern(regexp = "^(MALE|FEMALE)?$", message = "gender must be MALE, FEMALE, or omitted")
        String gender,
        @NotNull LocalDate hireDate,
        Long departmentId,
        String jobTitle,
        Long branchId,
        Long userId,
        // Contact fields (ADR-0040 D-11)
        String phone,
        @Email(message = "email: must be a valid email address")
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
