package com.erp.modules.subscription.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;

/**
 * A payment the platform operator recorded against a tenant's subscription (ADR-0069 D-8).
 * Append-only: getters only, no version, a correction is a new row.
 */
@Getter
@Entity
@Table(name = "subscription_payments")
public class SubscriptionPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, updatable = false, length = 26)
    private String uid;

    @Column(name = "organisation_id", nullable = false, updatable = false)
    private Long organisationId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, updatable = false, length = 3)
    private String currencyCode;

    @Column(name = "paid_on", nullable = false, updatable = false)
    private LocalDate paidOn;

    @Column(name = "paid_until", nullable = false, updatable = false)
    private LocalDate paidUntil;

    @Column(name = "reference", updatable = false, length = 100)
    private String reference;

    @Column(name = "note", updatable = false, length = 500)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    protected SubscriptionPayment() {
        // JPA
    }

    public SubscriptionPayment(Long organisationId, BigDecimal amount, String currencyCode,
                               LocalDate paidOn, LocalDate paidUntil, String reference, String note,
                               Long createdBy) {
        this.organisationId = organisationId;
        this.amount = amount;
        this.currencyCode = currencyCode;
        this.paidOn = paidOn;
        this.paidUntil = paidUntil;
        this.reference = reference;
        this.note = note;
        this.createdBy = createdBy;
    }

    @PrePersist
    void assignUid() {
        if (uid == null) {
            uid = com.erp.platform.common.domain.Ulid.next();
        }
    }
}
