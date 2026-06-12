package com.erp.modules.sales.domain.entity;

import com.erp.modules.sales.domain.enums.BlanketStatus;
import com.erp.platform.common.domain.UidEntity;
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
 * Blanket order header — a frame contract committing a customer to a total volume (ADR-0029 D-7).
 * Individual SOs drawn against it decrement drawn_qty on the BlanketOrderLine.
 */
@Getter
@Entity
@Table(name = "blanket_orders")
public class BlanketOrder extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /** System-assigned BO-#### number. */
    @Column(name = "order_number", nullable = false, length = 30)
    @Setter
    private String orderNumber;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to", nullable = false)
    @Setter
    private LocalDate validTo;

    @Column(name = "total_committed_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal totalCommittedAmount = BigDecimal.ZERO;

    @Column(name = "total_drawn_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal totalDrawnAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private BlanketStatus status = BlanketStatus.ACTIVE;

    @Column(name = "notes", length = 500)
    @Setter
    private String notes;

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

    protected BlanketOrder() {}

    public BlanketOrder(Long companyId, Long branchId, Long customerId,
                        String currency, LocalDate validFrom, LocalDate validTo,
                        Long createdBy) {
        this.companyId  = companyId;
        this.branchId   = branchId;
        this.customerId = customerId;
        this.currency   = currency;
        this.validFrom  = validFrom;
        this.validTo    = validTo;
        this.createdBy  = createdBy;
    }
}
