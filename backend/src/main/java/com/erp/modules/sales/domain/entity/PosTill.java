package com.erp.modules.sales.domain.entity;

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
 * A POS till (physical or virtual cash register) registered to a branch (ADR-0029 D-5).
 * At most one session may be OPEN on a till at a time (partial unique index in DB).
 */
@Getter
@Entity
@Table(name = "pos_tills")
public class PosTill extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /**
     * Optional short code for the till (e.g. "T-001"). Nullable — generated externally or
     * left unset. Uniqueness on (company_id, name) is enforced by uq_pos_till_company_name.
     */
    @Column(name = "code", nullable = true, length = 30)
    @Setter
    private String code;

    /** Short human label, e.g. "Till 1". */
    @Column(name = "name", nullable = false, length = 60)
    @Setter
    private String name;

    /** FK → cash_bank_accounts(id); the drawer account for this till (NOT NULL). */
    @Column(name = "cash_bank_account_id", nullable = false)
    @Setter
    private Long cashBankAccountId;

    /**
     * P3: default selling price list for this till — soft-FK to price_lists(id). Nullable.
     * Scalar Long per cross-module soft-FK convention.
     */
    @Column(name = "default_price_list_id")
    @Setter
    private Long defaultPriceListId;

    /** P3: physical device/terminal identifier (e.g. card-reader serial). Nullable. */
    @Column(name = "device_terminal_id", length = 60)
    @Setter
    private String deviceTerminalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
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

    protected PosTill() {}

    public PosTill(Long companyId, Long branchId, String name,
                   Long cashBankAccountId, Long createdBy) {
        this.companyId          = companyId;
        this.branchId           = branchId;
        this.name               = name;
        this.cashBankAccountId  = cashBankAccountId;
        this.createdBy          = createdBy;
    }
}
