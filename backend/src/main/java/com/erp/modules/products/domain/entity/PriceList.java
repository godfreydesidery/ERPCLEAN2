package com.erp.modules.products.domain.entity;

import com.erp.modules.products.domain.enums.PriceListScope;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * Named selling-price set per company (FR-PROD-10, ADR-0007 D-7).
 * Code is user-supplied (short mnemonic like {@code RETAIL}), not auto-numbered.
 */
@Getter
@Entity
@Table(name = "price_lists")
public class PriceList extends UidEntity {

    /** FK → companies.id; tenant scope; never updated. */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** User-supplied short code, unique per company (e.g. {@code RETAIL}, {@code WHOLESALE}). */
    @Column(name = "code", nullable = false, length = 20)
    @Setter
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    @Setter
    private String name;

    // -------------------------------------------------------------------------
    // P2 D5 — pricing-resolution metadata (ADR-0041 D5 Tier-1)
    // -------------------------------------------------------------------------

    /** P2 D5: price-list currency (CurrencyCode value type → VARCHAR(3)). Null = company default. */
    @Column(name = "currency", length = 3)
    @Setter
    private CurrencyCode currency;

    /** P2 D5: validity window start (null = open-ended). */
    @Column(name = "effective_from")
    @Setter
    private LocalDate effectiveFrom;

    /** P2 D5: validity window end (null = open-ended). */
    @Column(name = "effective_to")
    @Setter
    private LocalDate effectiveTo;

    /** P2 D5: whether listed prices are VAT-inclusive. */
    @Column(name = "price_includes_vat", nullable = false)
    @Setter
    private boolean priceIncludesVat = false;

    /** P2 D5: marks the company default price list. */
    @Column(name = "is_default", nullable = false)
    @Setter
    private boolean isDefault = false;

    /** P2 D5: applicability scope (GLOBAL/CUSTOMER/BRANCH/SEGMENT). Recorded on the row. */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    @Setter
    private PriceListScope scope = PriceListScope.GLOBAL;

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

    protected PriceList() {
        // JPA
    }

    public PriceList(Long companyId, String code, String name, Long createdBy) {
        this.companyId = companyId;
        this.code = code;
        this.name = name;
        this.createdBy = createdBy;
    }
}
