package com.erp.modules.hr.domain.entity;

import com.erp.modules.hr.domain.enums.EmploymentStatus;
import com.erp.modules.hr.domain.enums.MaritalStatus;
import com.erp.modules.hr.domain.enums.PaymentMethod;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** Employee master record (ADR-0032 D-4). */
@Getter
@Entity
@Table(name = "employees")
public class Employee extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id")
    @Setter
    private Long branchId;

    @Column(name = "employee_number", nullable = false, length = 30)
    private String employeeNumber;

    @Column(name = "first_name", nullable = false, length = 80)
    @Setter
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 80)
    @Setter
    private String lastName;

    @Column(name = "national_id", length = 40)
    @Setter
    private String nationalId;

    @Column(name = "tin", length = 20)
    @Setter
    private String tin;

    @Column(name = "nssf_number", length = 40)
    @Setter
    private String nssfNumber;

    @Column(name = "heslb_number", length = 40)
    @Setter
    private String heslbNumber;

    @Column(name = "date_of_birth")
    @Setter
    private LocalDate dateOfBirth;

    @Column(name = "gender", length = 10)
    @Setter
    private String gender;

    @Column(name = "hire_date", nullable = false, updatable = false)
    private LocalDate hireDate;

    @Column(name = "department_id")
    @Setter
    private Long departmentId;

    @Column(name = "job_title", length = 120)
    @Setter
    private String jobTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Setter
    private EmploymentStatus status = EmploymentStatus.ACTIVE;

    @Column(name = "user_id")
    @Setter
    private Long userId;

    // ---- Lifecycle + HR profile + org (P2 D6, ADR-0041) ----

    @Column(name = "termination_date")
    @Setter
    private LocalDate terminationDate;

    @Column(name = "termination_reason", length = 255)
    @Setter
    private String terminationReason;

    @Column(name = "confirmation_date")
    @Setter
    private LocalDate confirmationDate;

    @Column(name = "probation_end_date")
    @Setter
    private LocalDate probationEndDate;

    /** Self soft-FK employees — reporting line. */
    @Column(name = "manager_id")
    @Setter
    private Long managerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "marital_status", length = 20)
    @Setter
    private MaritalStatus maritalStatus;

    @Column(name = "nationality", length = 80)
    @Setter
    private String nationality;

    /** Soft-FK positions. */
    @Column(name = "position_id")
    @Setter
    private Long positionId;

    // ---- Contact fields (ADR-0040 D-11) ----

    @Column(name = "phone", length = 40)
    @Setter
    private String phone;

    @Column(name = "email", length = 160)
    @Setter
    private String email;

    @Column(name = "address_line", length = 255)
    @Setter
    private String addressLine;

    @Column(name = "region", length = 120)
    @Setter
    private String region;

    @Column(name = "district", length = 120)
    @Setter
    private String district;

    @Column(name = "postal_address", length = 255)
    @Setter
    private String postalAddress;

    // ---- Payee / disbursement fields (ADR-0040 D-11) ----

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    @Setter
    private PaymentMethod paymentMethod;

    @Column(name = "bank_name", length = 120)
    @Setter
    private String bankName;

    @Column(name = "bank_branch", length = 120)
    @Setter
    private String bankBranch;

    @Column(name = "bank_account_no", length = 60)
    @Setter
    private String bankAccountNo;

    @Column(name = "bank_account_name", length = 120)
    @Setter
    private String bankAccountName;

    @Column(name = "mobile_money_no", length = 60)
    @Setter
    private String mobileMoneyNo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    @Setter
    private Instant updatedAt;

    @Column(name = "updated_by")
    @Setter
    private Long updatedBy;

    protected Employee() {}

    public Employee(Long companyId, Long branchId, String employeeNumber,
                    String firstName, String lastName, LocalDate hireDate, Long createdBy) {
        this.companyId      = companyId;
        this.branchId       = branchId;
        this.employeeNumber = employeeNumber;
        this.firstName      = firstName;
        this.lastName       = lastName;
        this.hireDate       = hireDate;
        this.createdBy      = createdBy;
    }

    public String getFullName() {
        return firstName + " " + lastName;
    }
}
