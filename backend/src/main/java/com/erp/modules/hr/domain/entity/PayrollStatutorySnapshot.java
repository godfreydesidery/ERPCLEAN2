package com.erp.modules.hr.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** Durable record of which statutory rate sets a payroll line applied (ADR-0032 D-6, NFR-HR-02). */
@Getter
@Entity
@Table(name = "payroll_statutory_snapshots")
public class PayrollStatutorySnapshot extends UidEntity {

    @Column(name = "payroll_line_id", nullable = false, updatable = false)
    private Long payrollLineId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "applied_paye_band_set_uid", length = 26)
    @Setter
    private String appliedPayeBandSetUid;

    @Column(name = "applied_nssf_set_uid", length = 26)
    @Setter
    private String appliedNssfSetUid;

    @Column(name = "applied_wcf_set_uid", length = 26)
    @Setter
    private String appliedWcfSetUid;

    @Column(name = "applied_sdl_set_uid", length = 26)
    @Setter
    private String appliedSdlSetUid;

    @Column(name = "applied_heslb_set_uid", length = 26)
    @Setter
    private String appliedHeslbSetUid;

    @Column(name = "pay_date", nullable = false, updatable = false)
    private LocalDate payDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected PayrollStatutorySnapshot() {}

    public PayrollStatutorySnapshot(Long payrollLineId, Long companyId, LocalDate payDate, Long createdBy) {
        this.payrollLineId = payrollLineId;
        this.companyId     = companyId;
        this.payDate       = payDate;
        this.createdBy     = createdBy;
    }
}
