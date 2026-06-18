package com.erp.modules.parties.domain.entity;

import com.erp.modules.parties.domain.enums.CreditStatus;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.platform.common.money.CurrencyCode;
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

    /**
     * Soft-FK to payment_terms(id) — scalar, no @ManyToOne (cross-module soft-FK convention).
     * When set, overrides payment_terms_days for due-date derivation (D-2, ADR-0040).
     * payment_terms_days is retained as deprecated fallback.
     */
    @Column(name = "payment_terms_id")
    @Setter
    private Long paymentTermsId;

    // -------------------------------------------------------------------------
    // ADR-0040 D-5 — credit-control status (V68)
    // -------------------------------------------------------------------------

    /**
     * Machine-driven credit status. WARNING is advisory; ON_HOLD and STOPPED are hard blocks at SO
     * confirm. Defaults to OK; updated by a credit-review job or manually by credit-control staff.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "credit_status", nullable = false, length = 20)
    @Setter
    private CreditStatus creditStatus = CreditStatus.OK;

    /** Manual hold flag; set by credit-control staff independently of the computed credit_status. */
    @Column(name = "manual_hold", nullable = false)
    @Setter
    private boolean manualHold = false;

    /** Free-text reason recorded when manualHold is set or credit_status is changed by staff. */
    @Column(name = "credit_hold_reason", length = 255)
    @Setter
    private String creditHoldReason;

    // -------------------------------------------------------------------------
    // P2 mechanical — tax exemption + default currency
    // -------------------------------------------------------------------------

    /** P2: customer tax-exemption flag. */
    @Column(name = "tax_exempt", nullable = false)
    @Setter
    private boolean taxExempt = false;

    /** P2: tax-exemption certificate/reference (set when taxExempt). */
    @Column(name = "tax_exemption_ref", length = 80)
    @Setter
    private String taxExemptionRef;

    /** P2: customer default transaction currency (CurrencyCode value type → VARCHAR(3)). */
    @Column(name = "default_currency", length = 3)
    @Setter
    private CurrencyCode defaultCurrency;

    protected Customer() {
        // JPA
    }

    public Customer(Long companyId, String code, PartyType partyType, String displayName,
                    CustomerKind customerKind, Long createdBy) {
        super(companyId, code, partyType, displayName, createdBy);
        this.customerKind = customerKind;
    }
}
