package com.erp.modules.purchases.domain.entity;

import com.erp.platform.common.money.CurrencyCode;
import com.erp.modules.purchases.domain.enums.SupplierQuoteStatus;
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
 * Supplier Quote — one per responding supplier per RFQ (ADR-0027 D-4).
 * quote_number (SQ-####) assigned at capture (D-10). Status: RECEIVED → AWARDED / NOT_AWARDED.
 */
@Getter
@Entity
@Table(name = "supplier_quotes")
public class SupplierQuote extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "quote_number", nullable = false, length = 30)
    private String quoteNumber;

    @Column(name = "rfq_id", nullable = false, updatable = false)
    private Long rfqId;

    @Column(name = "rfq_uid", nullable = false, length = 26, updatable = false)
    private String rfqUid;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private Long supplierId;

    @Column(name = "supplier_code", nullable = false, length = 20)
    private String supplierCode;

    @Column(name = "supplier_name", nullable = false, length = 200)
    private String supplierName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private SupplierQuoteStatus status = SupplierQuoteStatus.RECEIVED;

    @Column(name = "valid_until")
    @Setter
    private LocalDate validUntil;

    @Column(name = "lead_time_days")
    @Setter
    private Short leadTimeDays;

    @Column(name = "quote_total_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal quoteTotalAmount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

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

    protected SupplierQuote() {
        // JPA
    }

    public SupplierQuote(Long companyId, Long branchId, String quoteNumber,
                         Long rfqId, String rfqUid,
                         Long supplierId, String supplierCode, String supplierName,
                         String currency, Long createdBy) {
        this.companyId     = companyId;
        this.branchId      = branchId;
        this.quoteNumber   = quoteNumber;
        this.rfqId         = rfqId;
        this.rfqUid        = rfqUid;
        this.supplierId    = supplierId;
        this.supplierCode  = supplierCode;
        this.supplierName  = supplierName;
        this.currency      = CurrencyCode.ofNullable(currency);
        this.createdBy     = createdBy;
    }
}
