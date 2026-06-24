package com.erp.modules.purchases.domain.entity;

import com.erp.platform.common.domain.Ulid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * Per-unit serial / IMEI number captured on a Goods Receipt line (V76).
 *
 * <p>Does NOT extend {@link com.erp.platform.common.domain.UidEntity} — serial rows are
 * add-only on the GRN; no optimistic-lock column needed. Mirrors the ProductBarcode pattern.
 *
 * <p>goodsReceiptLineId is a scalar soft-FK (same pattern as GoodsReceiptLine
 * → purchaseOrderLineId). company_id is denormalised from the line for future tenant queries.
 */
@Getter
@Entity
@Table(name = "goods_receipt_line_serials")
public class GoodsReceiptLineSerial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, updatable = false, length = 26)
    private String uid;

    /** Scalar soft-FK → goods_receipt_lines.id. */
    @Column(name = "goods_receipt_line_id", nullable = false, updatable = false)
    private Long goodsReceiptLineId;

    /** Denormalised from the line; immutable. */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "serial_number", nullable = false, length = 80)
    private String serialNumber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    void assignUid() {
        if (uid == null) {
            uid = Ulid.next();
        }
    }

    protected GoodsReceiptLineSerial() {
        // JPA
    }

    public GoodsReceiptLineSerial(Long goodsReceiptLineId, Long companyId,
                                   String serialNumber, Long createdBy) {
        this.goodsReceiptLineId = goodsReceiptLineId;
        this.companyId          = companyId;
        this.serialNumber       = serialNumber;
        this.createdBy          = createdBy;
    }
}
