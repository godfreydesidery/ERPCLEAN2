package com.erp.modules.sales.domain.entity;

import com.erp.modules.sales.domain.enums.RestaurantOrderLineStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

/**
 * One item on a restaurant order (ADR-0065 D-5).
 *
 * <ul>
 *   <li><b>PENDING</b> — added, not sent. Quantity and note may change; the line may be deleted.</li>
 *   <li><b>SENT</b> — fired to a prep station in round {@code fireRound}. Immutable: only a void
 *       changes it. A further "+1" of a sent item is a NEW pending line.</li>
 *   <li><b>VOIDED</b> — cancelled after sending, with who, who approved, and why.</li>
 * </ul>
 *
 * <p>The station is copied onto the line when it is sent, so a later change to the routing never
 * rewrites where a slip actually went.
 */
@Getter
@Entity
@Table(name = "restaurant_order_lines")
public class RestaurantOrderLine extends UidEntity {

    /** Updatable: a split or a merge moves the line to another order (ADR-0066 D-2, D-3). */
    @Column(name = "restaurant_order_id", nullable = false)
    private Long restaurantOrderId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "unit_id", nullable = false, updatable = false)
    private Long unitId;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    /** Free text for the kitchen ("no sugar", "well done"); printed on the slip. */
    @Column(name = "note", length = 200)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RestaurantOrderLineStatus status = RestaurantOrderLineStatus.PENDING;

    @Column(name = "fire_round")
    private Short fireRound;

    @Column(name = "prep_station_id")
    private Long prepStationId;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "sent_by")
    private Long sentBy;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "voided_by")
    private Long voidedBy;

    @Column(name = "void_authorised_by")
    private Long voidAuthorisedBy;

    @Column(name = "void_reason", length = 255)
    private String voidReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected RestaurantOrderLine() {}

    public RestaurantOrderLine(RestaurantOrder order, int lineNo, Long productId, Long unitId,
                               BigDecimal quantity, String note, Long createdBy) {
        this.restaurantOrderId = order.getId();
        this.companyId         = order.getCompanyId();
        this.branchId          = order.getBranchId();
        this.lineNo            = lineNo;
        this.productId         = productId;
        this.unitId            = unitId;
        this.quantity          = quantity;
        this.note              = note;
        this.createdBy         = createdBy;
    }

    public boolean isPending() {
        return status == RestaurantOrderLineStatus.PENDING;
    }

    public boolean isSent() {
        return status == RestaurantOrderLineStatus.SENT;
    }

    public boolean isVoided() {
        return status == RestaurantOrderLineStatus.VOIDED;
    }

    /** Changes an unsent line. A sent line is immutable — the service refuses before this. */
    public void changePending(BigDecimal newQuantity, String newNote, Long by, Instant at) {
        requirePending();
        this.quantity  = newQuantity;
        this.note      = newNote;
        this.updatedBy = by;
        this.updatedAt = at;
    }

    /** Fires the line to {@code stationId} (null when the branch has no stations) in {@code round}. */
    public void markSent(short round, Long stationId, Long by, Instant at) {
        requirePending();
        this.status        = RestaurantOrderLineStatus.SENT;
        this.fireRound     = round;
        this.prepStationId = stationId;
        this.sentBy        = by;
        this.sentAt        = at;
    }

    /**
     * Splits {@code voidQty} off this SENT line into a new VOIDED line carrying the same send stamp,
     * and keeps the rest here as SENT. Used for a partial void ("one of the three teas").
     */
    public RestaurantOrderLine splitVoided(int newLineNo, BigDecimal voidQty, String reason, Long by,
                                           Long authorisedBy, Instant at) {
        if (!isSent()) {
            throw new IllegalStateException("Only a sent item can be voided.");
        }
        var voided = new RestaurantOrderLine();
        voided.restaurantOrderId = restaurantOrderId;
        voided.companyId         = companyId;
        voided.branchId          = branchId;
        voided.lineNo            = newLineNo;
        voided.productId         = productId;
        voided.unitId            = unitId;
        voided.quantity          = voidQty;
        voided.note              = note;
        voided.fireRound         = fireRound;
        voided.prepStationId     = prepStationId;
        voided.sentAt            = sentAt;
        voided.sentBy            = sentBy;
        voided.createdBy         = by;
        voided.markVoided(reason, by, authorisedBy, at);
        this.quantity  = quantity.subtract(voidQty);
        this.updatedBy = by;
        this.updatedAt = at;
        return voided;
    }

    /** Voids the whole SENT line. */
    public void voidWhole(String reason, Long by, Long authorisedBy, Instant at) {
        if (!isSent()) {
            throw new IllegalStateException("Only a sent item can be voided.");
        }
        markVoided(reason, by, authorisedBy, at);
    }

    /**
     * Moves this line, whatever its state, to [target] as its line [newLineNo]. Send and void stamps
     * travel with it, so the kitchen is never asked again for an item it already has.
     */
    public void moveTo(RestaurantOrder target, int newLineNo, Long by, Instant at) {
        this.restaurantOrderId = target.getId();
        this.lineNo            = newLineNo;
        this.updatedBy         = by;
        this.updatedAt         = at;
    }

    /**
     * Splits [qty] of this live line off onto [target] as a new line in the SAME state (pending or
     * sent, with the same round, station and stamps); this line keeps the rest. A split check —
     * "one of the three teas is mine" (ADR-0066 D-2).
     */
    public RestaurantOrderLine splitOff(RestaurantOrder target, int newLineNo, BigDecimal qty,
                                        Long by, Instant at) {
        if (isVoided()) {
            throw new IllegalStateException("A voided item cannot be moved.");
        }
        var copy = new RestaurantOrderLine();
        copy.restaurantOrderId = target.getId();
        copy.companyId         = companyId;
        copy.branchId          = branchId;
        copy.lineNo            = newLineNo;
        copy.productId         = productId;
        copy.unitId            = unitId;
        copy.quantity          = qty;
        copy.note              = note;
        copy.status            = status;
        copy.fireRound         = fireRound;
        copy.prepStationId     = prepStationId;
        copy.sentAt            = sentAt;
        copy.sentBy            = sentBy;
        copy.createdBy         = by;
        this.quantity  = quantity.subtract(qty);
        this.updatedBy = by;
        this.updatedAt = at;
        return copy;
    }

    private void markVoided(String reason, Long by, Long authorisedBy, Instant at) {
        this.status           = RestaurantOrderLineStatus.VOIDED;
        this.voidReason       = reason;
        this.voidedBy         = by;
        this.voidAuthorisedBy = authorisedBy;
        this.voidedAt         = at;
        this.updatedBy        = by;
        this.updatedAt        = at;
    }

    private void requirePending() {
        if (!isPending()) {
            throw new IllegalStateException("This item has already been sent and cannot be changed.");
        }
    }
}
