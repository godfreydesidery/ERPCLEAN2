package com.erp.modules.cashbank.domain.entity;

import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * A named cash/bank money location linked one-to-one to a GL 1xxx asset account
 * (ADR-0016 D-2a, BR-CASH-01). Append-only mutations; deactivation preferred over delete.
 */
@Getter
@Entity
@Table(name = "cash_bank_accounts")
public class CashBankAccount extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id")
    @Setter
    private Long branchId;

    @Column(name = "code", nullable = false, length = 30, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    @Setter
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 10, updatable = false)
    private CashBankAccountType accountType;

    @Column(name = "bank_name", length = 120)
    @Setter
    private String bankName;

    @Column(name = "bank_account_no", length = 60)
    @Setter
    private String bankAccountNo;

    @Column(name = "bank_branch", length = 120)
    @Setter
    private String bankBranch;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    /** FK → chart_of_accounts(id); the linked GL 1xxx asset account. */
    @Column(name = "gl_account_id", nullable = false, updatable = false)
    private Long glAccountId;

    /** At most one per company (partial-unique constraint on the DB). */
    @Column(name = "is_default", nullable = false)
    @Setter
    private boolean isDefault = false;

    @Column(name = "active", nullable = false)
    @Setter
    private boolean active = true;

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

    protected CashBankAccount() {
        // JPA
    }

    public CashBankAccount(Long companyId, Long branchId, String code, String name,
                            CashBankAccountType accountType, String currency,
                            Long glAccountId, boolean isDefault, Long createdBy) {
        this.companyId    = companyId;
        this.branchId     = branchId;
        this.code         = code;
        this.name         = name;
        this.accountType  = accountType;
        this.currency     = currency;
        this.glAccountId  = glAccountId;
        this.isDefault    = isDefault;
        this.createdBy    = createdBy;
    }
}
