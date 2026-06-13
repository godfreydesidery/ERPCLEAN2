package com.erp.modules.budgeting.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Budget header — scoped to (company, fiscal year, [cost_centre_value]) (ADR-0034 D-4a).
 *
 * <p>A budget with {@code cost_centre_value_id = null} is a company-wide budget (BR-BUD-03).
 * At most one budget per (company, FY, cost centre) is enforced by
 * {@code uq_budget_company_year_cc} + the partial unique {@code uq_budget_company_year_companywide}.
 *
 * <p>Budget numbers are allocated via {@link com.erp.modules.budgeting.service.BudgetingNumberGenerator}
 * (BUDGET-#### pattern, ADR-0034 D-11). Budgets post NOTHING to GL (BR-BUD-01, D-7).
 *
 * <p>{@code cost_centre_value_id} references ADR-0025's {@code dimension_values(id)} — no JPA
 * association cross-module (same scalar FK shape as ADR-0025's journal_lines tag, D-3).
 */
@Getter
@Entity
@Table(name = "budgets")
public class Budget extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "budget_number", nullable = false, length = 30)
    private String budgetNumber;

    @Column(name = "name", nullable = false, length = 160)
    @Setter
    private String name;

    @Column(name = "fiscal_year_id", nullable = false, updatable = false)
    private Long fiscalYearId;

    /**
     * Scalar FK → ADR-0025's {@code dimension_values(id)} in the COST_CENTRE slot.
     * NULL = company-wide budget (BR-BUD-03).
     */
    @Column(name = "cost_centre_value_id")
    private Long costCentreValueId;

    @Column(name = "notes", length = 500)
    @Setter
    private String notes;

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

    protected Budget() {
        // JPA
    }

    public Budget(Long companyId, String budgetNumber, String name, Long fiscalYearId,
                  Long costCentreValueId, String notes, Long createdBy) {
        this.companyId          = companyId;
        this.budgetNumber       = budgetNumber;
        this.name               = name;
        this.fiscalYearId       = fiscalYearId;
        this.costCentreValueId  = costCentreValueId;
        this.notes              = notes;
        this.createdBy          = createdBy;
    }
}
