package com.erp.modules.parties.domain.entity;

import com.erp.modules.parties.domain.enums.PaymentTermsBasis;
import com.erp.platform.common.domain.MasterStatus;
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
 * Shared payment-terms master (D-2, ADR-0040).
 * Referenced by customers and suppliers via a scalar soft-FK ({@code payment_terms_id}).
 * Due-date derivation at AR/AP service layer reads basis + net_days; optional early-payment
 * discount pair (discount_days / discount_percent) — both null or both set (DB CHECK).
 */
@Getter
@Entity
@Table(name = "payment_terms")
public class PaymentTerms extends UidEntity {

    /** FK → companies.id; tenant scope; never updated. */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** User-entered short code, unique per company (uq_payment_term_company_code). */
    @Column(name = "code", nullable = false, length = 30)
    @Setter
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    @Setter
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "basis", nullable = false, length = 30)
    @Setter
    private PaymentTermsBasis basis;

    /** Number of days added to the base date determined by {@link #basis}. Must be &ge; 0. */
    @Column(name = "net_days", nullable = false)
    @Setter
    private int netDays = 0;

    /** Optional early-payment discount days (both null or both set with discount_percent). */
    @Column(name = "discount_days")
    @Setter
    private Integer discountDays;

    /** Optional early-payment discount percentage (NUMERIC 5,2). */
    @Column(name = "discount_percent", precision = 5, scale = 2)
    @Setter
    private BigDecimal discountPercent;

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

    protected PaymentTerms() {
        // JPA
    }

    public PaymentTerms(Long companyId, String code, String name,
                        PaymentTermsBasis basis, int netDays, Long createdBy) {
        this.companyId = companyId;
        this.code = code;
        this.name = name;
        this.basis = basis;
        this.netDays = netDays;
        this.createdBy = createdBy;
    }
}
