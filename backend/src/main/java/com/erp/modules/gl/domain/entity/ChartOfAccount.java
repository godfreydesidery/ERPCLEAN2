package com.erp.modules.gl.domain.entity;

import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.NormalBalance;
import com.erp.platform.common.domain.MasterStatus;
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
 * A GL account in the chart of accounts (ADR-0013 D-2a, FR-GL-01).
 *
 * <p>Extends {@link UidEntity} (id + uid + @Version).
 * company_id is scalar Long — never updated. is_active gates new postings (BR-GL-04);
 * status is the MasterStatus lifecycle column. normal_balance is stored and service-derived
 * from account_type at write (BR-GL-12).
 */
@Getter
@Entity
@Table(name = "chart_of_accounts")
public class ChartOfAccount extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "account_code", nullable = false, length = 20)
    @Setter
    private String accountCode;

    @Column(name = "name", nullable = false, length = 160)
    @Setter
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    @Setter
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "normal_balance", nullable = false, length = 10)
    @Setter
    private NormalBalance normalBalance;

    /** Self-FK to parent account; reserved for later grouping, NULL in v1. */
    @Column(name = "parent_id")
    @Setter
    private Long parentId;

    /** Posting gate: inactive accounts are excluded from new postings (BR-GL-04). */
    @Column(name = "is_active", nullable = false)
    @Setter
    private boolean active = true;

    /**
     * When false, manual journal lines targeting this account are rejected at posting time.
     * Use for system-controlled accounts (e.g. control accounts, retained earnings) where
     * direct user entries would break subledger reconciliation.
     * Defaults to true — all accounts allow manual posting unless explicitly locked.
     */
    @Column(name = "allow_manual_posting", nullable = false)
    @Setter
    private boolean allowManualPosting = true;

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

    protected ChartOfAccount() {
        // JPA
    }

    public ChartOfAccount(Long companyId, String accountCode, String name,
                          AccountType accountType, Long createdBy) {
        this.companyId = companyId;
        this.accountCode = accountCode;
        this.name = name;
        this.accountType = accountType;
        this.normalBalance = NormalBalance.forType(accountType);
        this.createdBy = createdBy;
    }
}
