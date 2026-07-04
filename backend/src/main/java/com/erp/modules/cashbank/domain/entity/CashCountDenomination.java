package com.erp.modules.cashbank.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

/**
 * One denomination line of a {@link CashCount} (ADR-0050 D-7.5). A pure detail table scoped through
 * its parent — no {@code uid}/{@code version} (not independently addressable) and no
 * {@code company_id} (see ADR-0050 D-7.5 note). Replaced wholesale on each
 * {@code recordDenominations} call, never updated in place.
 */
@Getter
@Entity
@Table(name = "cash_count_denominations")
public class CashCountDenomination {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cash_count_id", nullable = false, updatable = false)
    private Long cashCountId;

    @Column(name = "denomination", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal denomination;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    /** denomination × quantity. */
    @Column(name = "line_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal lineAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected CashCountDenomination() {
        // JPA
    }

    public CashCountDenomination(Long cashCountId, BigDecimal denomination, int quantity,
                                  BigDecimal lineAmount, Long createdBy) {
        this.cashCountId  = cashCountId;
        this.denomination = denomination;
        this.quantity     = quantity;
        this.lineAmount   = lineAmount;
        this.createdBy    = createdBy;
    }
}
