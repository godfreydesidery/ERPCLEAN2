package com.erp.modules.hr.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Earning/deduction detail line within a payroll line (ADR-0032 D-6). */
@Getter
@Entity
@Table(name = "payroll_line_items")
public class PayrollLineItem extends UidEntity {

    @Column(name = "payroll_line_id", nullable = false, updatable = false)
    private Long payrollLineId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "pay_component_id")
    private Long payComponentId;

    @Column(name = "item_kind", nullable = false, length = 20)
    private String itemKind;

    @Column(name = "label", nullable = false, length = 120)
    private String label;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal amount;

    @Column(name = "gl_account_id")
    @Setter
    private Long glAccountId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected PayrollLineItem() {}

    public PayrollLineItem(Long payrollLineId, Long companyId, Long payComponentId,
                            String itemKind, String label, BigDecimal amount,
                            Long glAccountId, Long createdBy) {
        this.payrollLineId  = payrollLineId;
        this.companyId      = companyId;
        this.payComponentId = payComponentId;
        this.itemKind       = itemKind;
        this.label          = label;
        this.amount         = amount;
        this.glAccountId    = glAccountId;
        this.createdBy      = createdBy;
    }
}
