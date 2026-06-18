package com.erp.modules.products.domain.entity;

import com.erp.modules.products.domain.enums.DimensionType;
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
 * Per-company unit of measure master (brief §decisions, ADR-0007).
 * Code is user-supplied (like PriceList.code), unique per company.
 * Extends {@link UidEntity} (id + uid + version).
 */
@Getter
@Entity
@Table(name = "units_of_measure")
public class UnitOfMeasure extends UidEntity {

    /** FK → companies.id; tenant scope; never updated. */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** User-supplied short code, unique per company (e.g. {@code PCS}, {@code KG}). */
    @Column(name = "code", nullable = false, length = 20)
    @Setter
    private String code;

    @Column(name = "name", nullable = false, length = 60)
    @Setter
    private String name;

    /** P2 D5: physical dimension family (COUNT/WEIGHT/VOLUME/LENGTH/TIME). Defaults to COUNT. */
    @Enumerated(EnumType.STRING)
    @Column(name = "dimension_type", nullable = false, length = 20)
    @Setter
    private DimensionType dimensionType = DimensionType.COUNT;

    /** P2 D5: display/rounding scale for quantities in this unit (0–6). */
    @Column(name = "decimal_places", nullable = false)
    @Setter
    private short decimalPlaces = 0;

    /** P2 D5: whether fractional quantities are allowed in this unit. */
    @Column(name = "is_fractional", nullable = false)
    @Setter
    private boolean fractional = true;

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

    protected UnitOfMeasure() {
        // JPA
    }

    public UnitOfMeasure(Long companyId, String code, String name, Long createdBy) {
        this.companyId = companyId;
        this.code = code;
        this.name = name;
        this.createdBy = createdBy;
    }
}
