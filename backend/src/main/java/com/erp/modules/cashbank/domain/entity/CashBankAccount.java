package com.erp.modules.cashbank.domain.entity;

import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

    // -------------------------------------------------------------------------
    // P2 — bank-identifier detail (BANK accounts). All nullable.
    // -------------------------------------------------------------------------

    /** P2: International Bank Account Number. Nullable. */
    @Column(name = "iban", length = 34)
    @Setter
    private String iban;

    /** P2: SWIFT code. Nullable. */
    @Column(name = "swift", length = 11)
    @Setter
    private String swift;

    /** P2: Bank Identifier Code. Nullable. */
    @Column(name = "bic", length = 11)
    @Setter
    private String bic;

    /** P2: domestic sort / routing code. Nullable. */
    @Column(name = "sort_code", length = 20)
    @Setter
    private String sortCode;

    // -------------------------------------------------------------------------
    // P2 — treasury limits + cached reconciliation figures. All nullable.
    // -------------------------------------------------------------------------

    /** P2: agreed overdraft limit. Nullable. */
    @Column(name = "overdraft_limit", precision = 19, scale = 4)
    @Setter
    private BigDecimal overdraftLimit;

    /** P2: minimum-balance floor. Nullable. */
    @Column(name = "minimum_balance", precision = 19, scale = 4)
    @Setter
    private BigDecimal minimumBalance;

    /** P2: date of last completed reconciliation. Nullable. */
    @Column(name = "last_reconciled_date")
    @Setter
    private LocalDate lastReconciledDate;

    /** P2: balance at last reconciliation. Nullable. */
    @Column(name = "last_reconciled_balance", precision = 19, scale = 4)
    @Setter
    private BigDecimal lastReconciledBalance;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

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

    /**
     * P3 (X3): MasterStatus soft-delete lifecycle alongside the legacy {@link #active} boolean
     * (additive — approval_policies precedent). DEFAULT ACTIVE; the boolean is kept for back-compat.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Setter
    private MasterStatus status = MasterStatus.ACTIVE;

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
        this.currency     = CurrencyCode.of(currency);
        this.glAccountId  = glAccountId;
        this.isDefault    = isDefault;
        this.createdBy    = createdBy;
    }
}
