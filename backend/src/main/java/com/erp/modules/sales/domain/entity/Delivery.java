package com.erp.modules.sales.domain.entity;

import com.erp.modules.sales.domain.enums.DeliveryStatus;
import com.erp.platform.common.domain.UidEntity;
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
 * Delivery header (ADR-0021 D-7).
 *
 * <p>Created in CONFIRMED status in v1 (picking/packing deferred). DEL-#### at create.
 * Emits DELIVERY.CONFIRMED outbox event in the same TX.
 */
@Getter
@Entity
@Table(name = "deliveries")
public class Delivery extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "delivery_number", nullable = false, length = 30)
    @Setter
    private String deliveryNumber;

    @Column(name = "sales_order_id", nullable = false, updatable = false)
    private Long salesOrderId;

    @Column(name = "sales_order_uid", nullable = false, length = 26, updatable = false)
    private String salesOrderUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private DeliveryStatus status = DeliveryStatus.CONFIRMED;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "delivery_date", nullable = false)
    private LocalDate deliveryDate;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt = Instant.now();

    @Column(name = "cogs_gl_entry_uid", length = 26)
    @Setter
    private String cogsGlEntryUid;

    @Column(name = "notes", length = 500)
    @Setter
    private String notes;

    // --- projects (ADR-0033 D-3, V66) ---
    @Column(name = "project_id")
    @Setter
    private Long projectId;

    @Column(name = "project_task_id")
    @Setter
    private Long projectTaskId;

    // --- ADR-0040 D-3 — ship-to address snapshot fields ---
    /** FK → customer_addresses.id; nullable — delivery address used for this delivery. */
    @Column(name = "ship_to_address_id")
    @Setter
    private Long shipToAddressId;

    /** Free-text shipping address override / snapshot (at most 500 chars). */
    @Column(name = "ship_to_address_text", length = 500)
    @Setter
    private String shipToAddressText;

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

    protected Delivery() {
        // JPA
    }

    public Delivery(Long companyId, Long branchId, Long salesOrderId, String salesOrderUid,
                    Long customerId, LocalDate deliveryDate, Long createdBy) {
        this.companyId = companyId;
        this.branchId = branchId;
        this.salesOrderId = salesOrderId;
        this.salesOrderUid = salesOrderUid;
        this.customerId = customerId;
        this.deliveryDate = deliveryDate;
        this.createdBy = createdBy;
    }
}
