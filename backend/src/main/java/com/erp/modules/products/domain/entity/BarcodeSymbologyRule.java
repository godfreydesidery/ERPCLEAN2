package com.erp.modules.products.domain.entity;

import com.erp.modules.products.domain.enums.SymbologyItemMatch;
import com.erp.modules.products.domain.enums.SymbologyValueKind;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.Ulid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-company rule describing how to decode a variable-digit (type-2) barcode whose trailing
 * digits carry an embedded WEIGHT or PRICE (ADR-0044 D-1a / BR-9).
 *
 * <p>Does NOT extend {@link com.erp.platform.common.domain.UidEntity} — the V73 table has no
 * {@code version} column (rules are admin-only, low-contention; no optimistic-lock needed).
 * Carries its own {@code @Id} + {@code uid} + {@code @PrePersist} following the same pattern
 * as {@link ProductBarcode}.
 *
 * <p>Maps to {@code barcode_symbology_rules} created by V73.
 */
@Getter
@Entity
@Table(name = "barcode_symbology_rules")
public class BarcodeSymbologyRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, updatable = false, length = 26)
    private String uid;

    /** FK → companies.id; tenant scope; immutable after creation. */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** Per-company unique code (e.g. "EAN13-W", "GS1-P"). */
    @Column(name = "code", nullable = false, updatable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    @Setter
    private String name;

    /**
     * Leading digits selecting this rule (e.g. "21", "02").
     * A scanned barcode matches when it starts with this value.
     */
    @Column(name = "prefix", nullable = false, length = 8)
    @Setter
    private String prefix;

    /** What the embedded numeric field encodes — WEIGHT or PRICE. */
    @Enumerated(EnumType.STRING)
    @Column(name = "value_kind", nullable = false, length = 10)
    @Setter
    private SymbologyValueKind valueKind;

    /** 0-based start index of the item-code substring within the barcode. */
    @Column(name = "item_code_start", nullable = false)
    @Setter
    private short itemCodeStart;

    /** Length (in characters) of the item-code substring. */
    @Column(name = "item_code_length", nullable = false)
    @Setter
    private short itemCodeLength;

    /** 0-based start index of the embedded value substring within the barcode. */
    @Column(name = "value_start", nullable = false)
    @Setter
    private short valueStart;

    /** Length (in characters) of the embedded value substring. */
    @Column(name = "value_length", nullable = false)
    @Setter
    private short valueLength;

    /**
     * Implied decimal places for the extracted integer.
     * {@code decoded = rawInteger / 10^value_decimals}.
     * E.g. value_decimals=3 turns raw "01500" → 1.500 kg.
     */
    @Column(name = "value_decimals", nullable = false)
    @Setter
    private short valueDecimals = 0;

    /**
     * How the extracted item-code substring resolves to a product:
     * BARCODE → look up {@code product_barcodes.barcode};
     * PRODUCT_CODE → look up {@code products.code}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "item_match", nullable = false, length = 16)
    @Setter
    private SymbologyItemMatch itemMatch = SymbologyItemMatch.BARCODE;

    /**
     * Label check-digit algorithm name. NULL = none (v1 ignores check digits).
     * Reserved for future use.
     */
    @Column(name = "check_digit_mode", length = 16)
    @Setter
    private String checkDigitMode;

    /**
     * Rule evaluation priority. Lower value = higher precedence.
     * When two rules both match a barcode prefix, the one with lower priority wins.
     */
    @Column(name = "priority", nullable = false)
    @Setter
    private int priority = 100;

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

    @PrePersist
    void assignUid() {
        if (uid == null) {
            uid = Ulid.next();
        }
    }

    protected BarcodeSymbologyRule() {
        // JPA
    }

    public BarcodeSymbologyRule(Long companyId, String code, String name,
                                String prefix, SymbologyValueKind valueKind,
                                short itemCodeStart, short itemCodeLength,
                                short valueStart, short valueLength,
                                short valueDecimals, SymbologyItemMatch itemMatch,
                                int priority, Long createdBy) {
        this.companyId = companyId;
        this.code = code;
        this.name = name;
        this.prefix = prefix;
        this.valueKind = valueKind;
        this.itemCodeStart = itemCodeStart;
        this.itemCodeLength = itemCodeLength;
        this.valueStart = valueStart;
        this.valueLength = valueLength;
        this.valueDecimals = valueDecimals;
        this.itemMatch = itemMatch;
        this.priority = priority;
        this.createdBy = createdBy;
    }
}
