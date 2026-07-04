package com.erp.modules.cashbank.domain.entity;

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
import lombok.Getter;
import lombok.Setter;

/**
 * A petty-cash imprest fund (ADR-0050 D-7 PR-B). RECORD-ONLY this slice: fund movements are
 * captured as {@link PettyCashTransaction} rows but never posted to GL — there is no
 * {@code journal_entry_ref} on the fund itself, and the transaction's own reserved column stays
 * {@code null} until a fast-follow ADR adds posting.
 *
 * <p>{@code balanceAmount} has no public setter — the only way it may change is through the
 * hand-written {@link #applyDisbursement}/{@link #applyReplenishment}/{@link #applyAdjustment}
 * methods, so it can never silently drift from the transaction ledger.
 */
@Getter
@Entity
@Table(name = "petty_cash_funds")
public class PettyCashFund extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "code", nullable = false, length = 30, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    @Setter
    private String name;

    /** app_users.id of the custodian responsible for the float; nullable (soft-FK). */
    @Column(name = "custodian_id")
    @Setter
    private Long custodianId;

    /** Authorised imprest ceiling — informational; does not hard-gate disbursements. */
    @Column(name = "float_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal floatAmount;

    /** Current cash on hand. Mutated only via {@code apply*()} — never a public setter. */
    @Column(name = "balance_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAmount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

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

    protected PettyCashFund() {
        // JPA
    }

    public PettyCashFund(Long companyId, Long branchId, String code, String name, Long custodianId,
                          BigDecimal floatAmount, String currency, Long createdBy) {
        this.companyId     = companyId;
        this.branchId      = branchId;
        this.code          = code;
        this.name          = name;
        this.custodianId   = custodianId;
        this.floatAmount   = floatAmount != null ? floatAmount : BigDecimal.ZERO;
        this.balanceAmount = BigDecimal.ZERO;
        this.currency      = CurrencyCode.of(currency);
        this.createdBy     = createdBy;
    }

    // -------------------------------------------------------------------------
    // Domain behaviour — the ONLY way balanceAmount may change (ADR-0050 D-7 PR-B)
    // -------------------------------------------------------------------------

    /** Decrements the float by {@code amount} (a disbursement paid out of the fund). Returns the new balance. */
    public BigDecimal applyDisbursement(BigDecimal amount) {
        this.balanceAmount = this.balanceAmount.subtract(amount);
        return this.balanceAmount;
    }

    /** Increments the float by {@code amount} (a replenishment topping the fund back up). Returns the new balance. */
    public BigDecimal applyReplenishment(BigDecimal amount) {
        this.balanceAmount = this.balanceAmount.add(amount);
        return this.balanceAmount;
    }

    /**
     * Applies a signed correction — positive increases the float, negative decreases it.
     * Returns the new balance.
     */
    public BigDecimal applyAdjustment(BigDecimal signedAmount) {
        this.balanceAmount = this.balanceAmount.add(signedAmount);
        return this.balanceAmount;
    }
}
