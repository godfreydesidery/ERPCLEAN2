package com.erp.modules.gl.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

/**
 * One leg of a journal entry (ADR-0013 D-2c/D-3, BR-GL-08).
 * Append-only: no updated_* columns (BR-GL-02). Exactly one of debit/credit is > 0 (enforced by
 * chk_journal_line_one_side in the DB + service validation). All amounts in company base currency.
 */
@Getter
@Entity
@Table(name = "journal_lines")
public class JournalLine extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** Analysis tag; nullable. */
    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "entry_id", nullable = false, updatable = false)
    private Long entryId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(name = "debit_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal debitAmount = BigDecimal.ZERO;

    @Column(name = "credit_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal creditAmount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "line_memo", length = 255)
    private String lineMemo;

    // --- cost-centre (ADR-0025 D-3) — nullable dimension slot tags ---
    // Scalar Long ids, no cross-module @ManyToOne (D-7). FK to dimension_values(id) in DB.
    /** Cost Centre slot tag (nullable — untagged when null). */
    @Column(name = "cost_centre_value_id")
    private Long costCentreValueId;

    /** Department slot tag (nullable). */
    @Column(name = "department_value_id")
    private Long departmentValueId;

    /** Reserved for a third named dimension (slot 3 — no v1 poster writes this). */
    @Column(name = "dimension3_value_id")
    private Long dimension3ValueId;

    /** Reserved for a future dimension (slot 4 — no v1 poster writes this). */
    @Column(name = "dimension4_value_id")
    private Long dimension4ValueId;

    // --- projects (ADR-0033 D-1, V64) — dedicated scalar project dimension tags ---
    /** FK → projects(id); nullable — set by InventoryGlPoster on ISSUE_TO_PROJECT journals. */
    @Column(name = "project_id")
    private Long projectId;

    /** FK → project_tasks(id); nullable. */
    @Column(name = "project_task_id")
    private Long projectTaskId;

    /** ProjectCostType name (MATERIAL/LABOUR/OVERHEAD/…); nullable. */
    @Column(name = "project_cost_type", length = 20)
    private String projectCostType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected JournalLine() {
        // JPA
    }

    /** Debit line (debit_amount > 0, credit_amount = 0). */
    public static JournalLine debit(Long companyId, Long branchId, Long entryId, int lineNo,
                                    Long accountId, BigDecimal amount, String currency,
                                    String lineMemo, Long createdBy) {
        return debit(companyId, branchId, entryId, lineNo, accountId, amount, currency,
                     lineMemo, createdBy, null, null, null, null);
    }

    /**
     * Debit line with dimension tags (ADR-0025 D-3/D-4).
     * All four dimension ids are nullable — null = untagged for that slot.
     */
    public static JournalLine debit(Long companyId, Long branchId, Long entryId, int lineNo,
                                    Long accountId, BigDecimal amount, String currency,
                                    String lineMemo, Long createdBy,
                                    Long costCentreValueId, Long departmentValueId,
                                    Long dimension3ValueId, Long dimension4ValueId) {
        return debit(companyId, branchId, entryId, lineNo, accountId, amount, currency,
                     lineMemo, createdBy, costCentreValueId, departmentValueId,
                     dimension3ValueId, dimension4ValueId, null, null, null);
    }

    /**
     * Debit line with dimension + project tags (ADR-0033 D-1).
     */
    public static JournalLine debit(Long companyId, Long branchId, Long entryId, int lineNo,
                                    Long accountId, BigDecimal amount, String currency,
                                    String lineMemo, Long createdBy,
                                    Long costCentreValueId, Long departmentValueId,
                                    Long dimension3ValueId, Long dimension4ValueId,
                                    Long projectId, Long projectTaskId, String projectCostType) {
        JournalLine l = new JournalLine();
        l.companyId          = companyId;
        l.branchId           = branchId;
        l.entryId            = entryId;
        l.lineNo             = lineNo;
        l.accountId          = accountId;
        l.debitAmount        = amount;
        l.creditAmount       = BigDecimal.ZERO;
        l.currency           = currency;
        l.lineMemo           = lineMemo;
        l.createdBy          = createdBy;
        l.costCentreValueId  = costCentreValueId;
        l.departmentValueId  = departmentValueId;
        l.dimension3ValueId  = dimension3ValueId;
        l.dimension4ValueId  = dimension4ValueId;
        l.projectId          = projectId;
        l.projectTaskId      = projectTaskId;
        l.projectCostType    = projectCostType;
        return l;
    }

    /** Credit line (credit_amount > 0, debit_amount = 0). */
    public static JournalLine credit(Long companyId, Long branchId, Long entryId, int lineNo,
                                     Long accountId, BigDecimal amount, String currency,
                                     String lineMemo, Long createdBy) {
        return credit(companyId, branchId, entryId, lineNo, accountId, amount, currency,
                      lineMemo, createdBy, null, null, null, null);
    }

    /**
     * Credit line with dimension tags (ADR-0025 D-3/D-4).
     * All four dimension ids are nullable — null = untagged for that slot.
     */
    public static JournalLine credit(Long companyId, Long branchId, Long entryId, int lineNo,
                                     Long accountId, BigDecimal amount, String currency,
                                     String lineMemo, Long createdBy,
                                     Long costCentreValueId, Long departmentValueId,
                                     Long dimension3ValueId, Long dimension4ValueId) {
        return credit(companyId, branchId, entryId, lineNo, accountId, amount, currency,
                      lineMemo, createdBy, costCentreValueId, departmentValueId,
                      dimension3ValueId, dimension4ValueId, null, null, null);
    }

    /**
     * Credit line with dimension + project tags (ADR-0033 D-1).
     */
    public static JournalLine credit(Long companyId, Long branchId, Long entryId, int lineNo,
                                     Long accountId, BigDecimal amount, String currency,
                                     String lineMemo, Long createdBy,
                                     Long costCentreValueId, Long departmentValueId,
                                     Long dimension3ValueId, Long dimension4ValueId,
                                     Long projectId, Long projectTaskId, String projectCostType) {
        JournalLine l = new JournalLine();
        l.companyId          = companyId;
        l.branchId           = branchId;
        l.entryId            = entryId;
        l.lineNo             = lineNo;
        l.accountId          = accountId;
        l.debitAmount        = BigDecimal.ZERO;
        l.creditAmount       = amount;
        l.currency           = currency;
        l.lineMemo           = lineMemo;
        l.createdBy          = createdBy;
        l.costCentreValueId  = costCentreValueId;
        l.departmentValueId  = departmentValueId;
        l.dimension3ValueId  = dimension3ValueId;
        l.dimension4ValueId  = dimension4ValueId;
        l.projectId          = projectId;
        l.projectTaskId      = projectTaskId;
        l.projectCostType    = projectCostType;
        return l;
    }
}
