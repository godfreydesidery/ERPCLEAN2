package com.erp.modules.parties.domain.entity;

import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.platform.common.money.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Customer master (FR-PARTY-01, ADR-0006 D-2).
 * Sub-kind: {@link CustomerKind#CASH_WALK_IN} or {@link CustomerKind#CREDIT_ACCOUNT}.
 * Credit limit is a {@link Money} pair per ADR-0005 D-1; null for walk-in customers.
 */
@Getter
@Entity
@Table(name = "customers")
public class Customer extends PartyBase {

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_kind", nullable = false, length = 20)
    @Setter
    private CustomerKind customerKind;

    /**
     * Credit limit — null for {@link CustomerKind#CASH_WALK_IN}.
     * Both columns null or both set, enforced by DB CHECK + the {@link Money} constructor.
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount",   column = @Column(name = "credit_limit_amount",
                    precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "credit_limit_currency",
                    length = 3))
    })
    @Setter
    private Money creditLimit;

    /** Optional payment terms for credit customers (recording-only in v1). */
    @Column(name = "payment_terms_days")
    @Setter
    private Integer paymentTermsDays;

    protected Customer() {
        // JPA
    }

    public Customer(Long companyId, String code, PartyType partyType, String displayName,
                    CustomerKind customerKind, Long createdBy) {
        super(companyId, code, partyType, displayName, createdBy);
        this.customerKind = customerKind;
    }
}
