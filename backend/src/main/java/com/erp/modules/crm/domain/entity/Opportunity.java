package com.erp.modules.crm.domain.entity;

import com.erp.modules.crm.domain.enums.OpportunityStatus;
import com.erp.platform.common.domain.MasterStatus;
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

/**
 * CRM opportunity — a qualified, valued deal against a Parties customer (ADR-0031 D-5).
 * Stage (FK pipeline_stage_id) and status (OpportunityStatus) are orthogonal axes (D-2, BR-CRM-04).
 */
@Getter
@Entity
@Table(name = "opportunities")
public class Opportunity extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "opportunity_number", nullable = false, length = 30)
    @Setter
    private String opportunityNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "opportunity_status", nullable = false, length = 20)
    @Setter
    private OpportunityStatus opportunityStatus = OpportunityStatus.OPEN;

    @Column(name = "title", nullable = false, length = 200)
    @Setter
    private String title;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_uid", nullable = false, length = 26)
    private String customerUid;

    @Column(name = "agent_id")
    @Setter
    private Long agentId;

    @Column(name = "owner_user_id")
    @Setter
    private Long ownerUserId;

    @Column(name = "source_lead_id")
    @Setter
    private Long sourceLeadId;

    @Column(name = "source_lead_uid", length = 26)
    @Setter
    private String sourceLeadUid;

    @Column(name = "pipeline_stage_id", nullable = false)
    @Setter
    private Long pipelineStageId;

    @Column(name = "win_probability", nullable = false, precision = 5, scale = 2)
    @Setter
    private BigDecimal winProbability = BigDecimal.ZERO;

    @Column(name = "estimated_value_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal estimatedValueAmount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "expected_close_date")
    @Setter
    private LocalDate expectedCloseDate;

    @Column(name = "won_at")
    @Setter
    private Instant wonAt;

    @Column(name = "lost_at")
    @Setter
    private Instant lostAt;

    @Column(name = "loss_reason", length = 255)
    @Setter
    private String lossReason;

    @Column(name = "converted_document_kind", length = 20)
    @Setter
    private String convertedDocumentKind;

    @Column(name = "converted_document_uid", length = 26)
    @Setter
    private String convertedDocumentUid;

    @Column(name = "converted_at")
    @Setter
    private Instant convertedAt;

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

    protected Opportunity() { /* JPA */ }

    public Opportunity(Long companyId, Long branchId, String title,
                       Long customerId, String customerUid, String currency,
                       Long pipelineStageId, BigDecimal winProbability, Long createdBy) {
        this.companyId = companyId;
        this.branchId = branchId;
        this.title = title;
        this.customerId = customerId;
        this.customerUid = customerUid;
        this.currency = currency;
        this.pipelineStageId = pipelineStageId;
        this.winProbability = winProbability;
        this.createdBy = createdBy;
    }
}
