package com.erp.modules.tax.domain.entity;

import com.erp.modules.tax.domain.enums.VatAdjustmentReason;
import com.erp.modules.tax.domain.enums.VatAdjustmentSign;
import com.erp.platform.common.domain.UidEntity;
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
 * Manual VAT adjustment line on a DRAFT return (ADR-0017 D-2c).
 * May only be added/removed while the parent return is DRAFT (BR-VAT-09, service-enforced).
 */
@Getter
@Entity
@Table(name = "vat_adjustments")
public class VatAdjustment extends UidEntity {

    @Column(name = "vat_return_id", nullable = false, updatable = false)
    private Long vatReturnId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private VatAdjustmentReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "sign", nullable = false, length = 10)
    private VatAdjustmentSign sign;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "narrative", length = 255)
    @Setter
    private String narrative;

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

    protected VatAdjustment() {
        // JPA
    }

    public VatAdjustment(Long vatReturnId, Long companyId,
                         VatAdjustmentReason reason, VatAdjustmentSign sign,
                         BigDecimal amount, String narrative, Long createdBy) {
        this.vatReturnId = vatReturnId;
        this.companyId   = companyId;
        this.reason      = reason;
        this.sign        = sign;
        this.amount      = amount;
        this.narrative   = narrative;
        this.createdBy   = createdBy;
    }

    /**
     * The signed contribution of this adjustment to the return's net VAT.
     * INCREASE adds to net; DECREASE reduces it.
     */
    public BigDecimal signedAmount() {
        return sign == VatAdjustmentSign.INCREASE ? amount : amount.negate();
    }
}
