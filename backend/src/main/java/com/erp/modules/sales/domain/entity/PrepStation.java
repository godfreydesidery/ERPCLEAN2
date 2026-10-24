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
 * A place that prepares items and gets a slip — Kitchen, Bar/Barista (ADR-0065 D-4).
 *
 * <p>The printer address is where a TILL sends the slip (raw ESC/POS over TCP). The server never
 * prints: it may be hosted where the restaurant's LAN printers are unreachable. A till may override
 * the address with a local printer on its own device.
 *
 * <p>{@code defaultStation} marks the branch's catch-all: items whose category is not routed go
 * there. At most one ACTIVE default per branch ({@code ux_prep_station_one_default}).
 */
@Getter
@Entity
@Table(name = "prep_stations")
public class PrepStation extends UidEntity {

    public static final int DEFAULT_PRINTER_PORT = 9100;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "name", nullable = false, length = 60)
    @Setter
    private String name;

    @Column(name = "printer_host", length = 255)
    @Setter
    private String printerHost;

    @Column(name = "printer_port", nullable = false)
    @Setter
    private Integer printerPort = DEFAULT_PRINTER_PORT;

    @Column(name = "is_default", nullable = false)
    @Setter
    private boolean defaultStation;

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

    protected PrepStation() {}

    public PrepStation(Long companyId, Long branchId, String name, Long createdBy) {
        this.companyId = companyId;
        this.branchId  = branchId;
        this.name      = name;
        this.createdBy = createdBy;
    }
}
