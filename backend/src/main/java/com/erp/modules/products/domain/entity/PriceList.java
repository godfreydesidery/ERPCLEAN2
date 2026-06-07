package com.erp.modules.products.domain.entity;

import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
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
