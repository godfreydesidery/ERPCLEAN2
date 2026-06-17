package com.erp.modules.tax.domain.entity;

import com.erp.platform.common.money.CurrencyCode;
import com.erp.modules.tax.domain.enums.WhtKind;
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
 * WHT register + certificate — one row per withholding event (ADR-0017 D-2e).
 * party_id is a kind-discriminated scalar (no hard FK to two tables).
 */
@Getter
@Entity
@Table(name = "wht_transactions")
public class WhtTransaction extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "wht_number", nullable = false, length = 30)
    private String whtNumber;

    @Column(name = "wht_type_id", nullable = false, updatable = false)
    private Long whtTypeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private WhtKind kind;

    @Column(name = "party_kind", nullable = false, length = 10)
    private String partyKind;

    @Column(name = "party_id", nullable = false)
    private Long partyId;

    @Column(name = "party_name", nullable = false, length = 160)
    private String partyName;

    @Column(name = "source_ref", nullable = false, length = 26)
    private String sourceRef;

    @Column(name = "taxable_base", nullable = false)
    private BigDecimal taxableBase;

    @Column(name = "wht_amount", nullable = false)
    private BigDecimal whtAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Column(name = "certificate_date", nullable = false)
    private LocalDate certificateDate;

    @Column(name = "journal_entry_ref", length = 26)
    @Setter
    private String journalEntryRef;

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

    protected WhtTransaction() {
        // JPA
    }

    public WhtTransaction(Long companyId, Long branchId, String whtNumber,
                          Long whtTypeId, WhtKind kind,
                          String partyKind, Long partyId, String partyName,
                          String sourceRef, BigDecimal taxableBase, BigDecimal whtAmount,
                          String currency, LocalDate certificateDate, Long createdBy) {
        this.companyId       = companyId;
        this.branchId        = branchId;
        this.whtNumber       = whtNumber;
        this.whtTypeId       = whtTypeId;
        this.kind            = kind;
        this.partyKind       = partyKind;
        this.partyId         = partyId;
        this.partyName       = partyName;
        this.sourceRef       = sourceRef;
        this.taxableBase     = taxableBase;
        this.whtAmount       = whtAmount;
        this.currency        = CurrencyCode.ofNullable(currency);
        this.certificateDate = certificateDate;
        this.createdBy       = createdBy;
    }
}
