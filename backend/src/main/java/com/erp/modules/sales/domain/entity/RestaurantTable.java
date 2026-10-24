package com.erp.modules.sales.domain.entity;

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
 * A table on a branch's floor (ADR-0065 D-3). Its live state (free / occupied / billed) is derived
 * from the branch's live orders and never stored here. Deactivated, never deleted.
 */
@Getter
@Entity
@Table(name = "restaurant_tables")
public class RestaurantTable extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /** Free text, unique per branch: "T1", "Terrace 3", "Bar stool 2". */
    @Column(name = "name", nullable = false, length = 40)
    @Setter
    private String name;

    @Column(name = "seats", nullable = false)
    @Setter
    private Short seats = 4;

    /** Optional grouping label on the floor, e.g. "Inside", "Terrace". */
    @Column(name = "area", length = 40)
    @Setter
    private String area;

    @Column(name = "sort_order", nullable = false)
    @Setter
    private Integer sortOrder = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private MasterStatus status = MasterStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    @Setter
    private Instant updatedAt;

    @Column(name = "updated_by")
    @Setter
    private Long updatedBy;

    protected RestaurantTable() {}

    public RestaurantTable(Long companyId, Long branchId, String name, Long createdBy) {
        this.companyId = companyId;
        this.branchId  = branchId;
        this.name      = name;
        this.createdBy = createdBy;
    }
}
