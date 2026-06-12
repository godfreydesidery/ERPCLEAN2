package com.erp.modules.stock.domain.entity;

import com.erp.modules.stock.domain.enums.SerialStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Per-unit serial number record (ADR-0028 D-7, FR-INVD-23..27).
 *
 * <p>Integrity invariant (BR-INVD-11): count of IN_STOCK serials at a location for a product
 * equals the {@code stock_on_hand.quantity} for that {@code (location, product)}.
 *
 * <p>{@code location_id} is NULL when ISSUED (the serial is out of stock).
 *
 * <p>Extends {@link UidEntity} — carries {@code @Version} via the superclass.
 * No Lombok on entities.
 */
@Entity
@Table(name = "stock_serials")
public class StockSerial extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /** Current location; NULL when ISSUED. */
    @Column(name = "location_id")
    private Long locationId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "serial_number", nullable = false, updatable = false, length = 80)
    private String serialNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "serial_status", nullable = false, length = 12)
    private SerialStatus serialStatus;

    @Column(name = "received_document_uid", length = 26)
    private String receivedDocumentUid;

    @Column(name = "issued_document_uid", length = 26)
    private String issuedDocumentUid;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected StockSerial() {
        // JPA
    }

    public StockSerial(Long companyId, Long branchId, Long locationId, Long productId,
                       String serialNumber, String receivedDocumentUid, Long createdBy) {
        this.companyId            = companyId;
        this.branchId             = branchId;
        this.locationId           = locationId;
        this.productId            = productId;
        this.serialNumber         = serialNumber;
        this.serialStatus         = SerialStatus.IN_STOCK;
        this.receivedDocumentUid  = receivedDocumentUid;
        this.createdBy            = createdBy;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Domain behaviour (status transitions)
    // -------------------------------------------------------------------------

    /** Issue the serial (sale/delivery). Records the issuing document uid and clears location. */
    public void issue(String issuedDocumentUid, Long actorId) {
        this.serialStatus      = SerialStatus.ISSUED;
        this.issuedDocumentUid = issuedDocumentUid;
        this.locationId        = null;
        this.updatedAt         = Instant.now();
        this.updatedBy         = actorId;
    }

    /** Return the serial; status becomes RETURNED and location is restored. */
    public void returnSerial(Long locationId, Long actorId) {
        this.serialStatus = SerialStatus.RETURNED;
        this.locationId   = locationId;
        this.updatedAt    = Instant.now();
        this.updatedBy    = actorId;
    }

    /** Restock a returned serial (IN_STOCK again at given location). */
    public void restock(Long locationId, Long actorId) {
        this.serialStatus = SerialStatus.IN_STOCK;
        this.locationId   = locationId;
        this.updatedAt    = Instant.now();
        this.updatedBy    = actorId;
    }

    /** Move location (transfer — FR-INVD-26). */
    public void moveLocation(Long newLocationId, Long actorId) {
        this.locationId = newLocationId;
        this.updatedAt  = Instant.now();
        this.updatedBy  = actorId;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Long         getCompanyId()           { return companyId; }
    public Long         getBranchId()            { return branchId; }
    public Long         getLocationId()          { return locationId; }
    public Long         getProductId()           { return productId; }
    public String       getSerialNumber()        { return serialNumber; }
    public SerialStatus getSerialStatus()        { return serialStatus; }
    public String       getReceivedDocumentUid() { return receivedDocumentUid; }
    public String       getIssuedDocumentUid()   { return issuedDocumentUid; }
    public Instant      getCreatedAt()           { return createdAt; }
    public Long         getCreatedBy()           { return createdBy; }
    public Instant      getUpdatedAt()           { return updatedAt; }
    public Long         getUpdatedBy()           { return updatedBy; }
}
