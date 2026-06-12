package com.erp.modules.budgeting.domain.entity;

import com.erp.modules.budgeting.domain.enums.BudgetVersionStatus;
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
 * One revision of a budget's lines, carrying the DRAFT→SUBMITTED→APPROVED/REJECTED/SUPERSEDED
 * lifecycle (ADR-0034 D-4b / D-5).
 *
 * <p>{@code @Version} (inherited from {@link com.erp.platform.common.domain.UidEntity}) is the
 * optimistic-lock guard for concurrent approval races (NFR-BUD-04).
 *
 * <p>{@code cost_centre_value_id} is denormalised from the parent budget for the
 * single-active-version partial unique index (D-4b). NULL = company-wide.
 *
 * <p>The DB backstop is the partial unique index {@code uq_budget_version_one_approved}
 * (+ {@code uq_budget_version_one_approved_companywide} for the NULL case). The service
 * serialises the supersede in one TX (D-5).
 */
@Getter
@Entity
@Table(name = "budget_versions")
public class BudgetVersion extends UidEntity {

    @Column(name = "budget_id", nullable = false, updatable = false)
    private Long budgetId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "fiscal_year_id", nullable = false, updatable = false)
    private Long fiscalYearId;

    /** Denormalised for the single-active-version partial unique; NULL = company-wide. */
    @Column(name = "cost_centre_value_id")
    private Long costCentreValueId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private BudgetVersionStatus status = BudgetVersionStatus.DRAFT;

    @Column(name = "label", length = 120)
    @Setter
    private String label;

    /** FK → budget_versions(id) self; null if not seeded from a prior version. */
    @Column(name = "seeded_from_version_id")
    private Long seededFromVersionId;

    @Column(name = "submitted_at")
    @Setter
    private Instant submittedAt;

    @Column(name = "submitted_by")
    @Setter
    private Long submittedBy;

    @Column(name = "approved_at")
    @Setter
    private Instant approvedAt;

    @Column(name = "approved_by")
    @Setter
    private Long approvedBy;

    @Column(name = "rejected_at")
    @Setter
    private Instant rejectedAt;

    @Column(name = "rejected_by")
    @Setter
    private Long rejectedBy;

    @Column(name = "superseded_at")
    @Setter
    private Instant supersededAt;

    @Column(name = "decision_reason", length = 500)
    @Setter
    private String decisionReason;

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

    protected BudgetVersion() {
        // JPA
    }

    public BudgetVersion(Long budgetId, Long companyId, Long fiscalYearId,
                         Long costCentreValueId, int versionNo, String label,
                         Long seededFromVersionId, Long createdBy) {
        this.budgetId             = budgetId;
        this.companyId            = companyId;
        this.fiscalYearId         = fiscalYearId;
        this.costCentreValueId    = costCentreValueId;
        this.versionNo            = versionNo;
        this.label                = label;
        this.seededFromVersionId  = seededFromVersionId;
        this.status               = BudgetVersionStatus.DRAFT;
        this.createdBy            = createdBy;
    }
}
