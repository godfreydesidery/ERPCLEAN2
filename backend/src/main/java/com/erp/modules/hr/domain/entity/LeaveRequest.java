package com.erp.modules.hr.domain.entity;

import com.erp.modules.hr.domain.enums.LeaveRequestStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** Employee leave request (ADR-0032 D-4). */
@Getter
@Entity
@Table(name = "leave_requests")
public class LeaveRequest extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "employee_id", nullable = false, updatable = false)
    private Long employeeId;

    @Column(name = "leave_type_id", nullable = false, updatable = false)
    private Long leaveTypeId;

    @Column(name = "from_date", nullable = false, updatable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false, updatable = false)
    private LocalDate toDate;

    @Column(name = "days", nullable = false, precision = 9, scale = 2, updatable = false)
    private BigDecimal days;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    @Setter
    private LeaveRequestStatus status = LeaveRequestStatus.PENDING;

    @Column(name = "decided_by")
    @Setter
    private Long decidedBy;

    @Column(name = "decided_at")
    @Setter
    private Instant decidedAt;

    @Column(name = "reason", length = 255)
    @Setter
    private String reason;

    @Column(name = "decision_note", length = 255)
    @Setter
    private String decisionNote;

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

    protected LeaveRequest() {}

    public LeaveRequest(Long companyId, Long employeeId, Long leaveTypeId,
                        LocalDate fromDate, LocalDate toDate, BigDecimal days,
                        String reason, Long createdBy) {
        this.companyId   = companyId;
        this.employeeId  = employeeId;
        this.leaveTypeId = leaveTypeId;
        this.fromDate    = fromDate;
        this.toDate      = toDate;
        this.days        = days;
        this.reason      = reason;
        this.createdBy   = createdBy;
    }
}
