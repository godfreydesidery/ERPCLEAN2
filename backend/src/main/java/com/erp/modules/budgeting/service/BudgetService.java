package com.erp.modules.budgeting.service;

import com.erp.modules.budgeting.domain.dto.ApproveBudgetVersionRequest;
import com.erp.modules.budgeting.domain.dto.BudgetDto;
import com.erp.modules.budgeting.domain.dto.BudgetVersionDto;
import com.erp.modules.budgeting.domain.dto.CreateBudgetRequest;
import com.erp.modules.budgeting.domain.dto.CreateBudgetVersionRequest;
import com.erp.modules.budgeting.domain.dto.RejectBudgetVersionRequest;
import com.erp.modules.budgeting.domain.dto.UpsertBudgetLineRequest;
import com.erp.modules.budgeting.domain.enums.BudgetVersionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Budget CRUD + version lifecycle (ADR-0034 D-1/D-5).
 *
 * <p>assertCanActIn on every read/write path (NFR-BUD-01, FR-BUD-20).
 * Every state transition is audited (FR-BUD-19).
 * Budgets post NOTHING to GL (BR-BUD-01, D-7).
 */
public interface BudgetService {

    /** Create a new budget + initial DRAFT version (FR-BUD-06). */
    BudgetDto createBudget(CreateBudgetRequest request);

    /** Get budget detail including all versions (FR-BUD-09). */
    BudgetDto getBudgetByUid(String uid);

    /** List budgets for a company (FR-BUD-09). */
    Page<BudgetDto> listBudgets(Long companyId, String fiscalYearUid,
                                String costCentreValueUid, BudgetVersionStatus versionStatus,
                                Pageable pageable);

    /** Create a new DRAFT version, optionally seeding from an existing version (FR-BUD-13). */
    BudgetVersionDto createVersion(String budgetUid, CreateBudgetVersionRequest request);

    /** Get version detail including lines. */
    BudgetVersionDto getVersionByUid(String uid);

    /** Upsert lines on a DRAFT version (FR-BUD-07/08; rejects if not DRAFT, BR-BUD-06). */
    BudgetVersionDto upsertLines(String versionUid, UpsertBudgetLineRequest request);

    /** Submit a DRAFT version → SUBMITTED (FR-BUD-11; rejects if no lines, BR-BUD-11). */
    BudgetVersionDto submit(String versionUid);

    /** Recall a SUBMITTED version → DRAFT (FR-BUD-14). */
    BudgetVersionDto recall(String versionUid);

    /**
     * Approve a SUBMITTED version → APPROVED; supersedes prior APPROVED version in one TX
     * (FR-BUD-12, D-5, BR-BUD-12, NFR-BUD-04).
     */
    BudgetVersionDto approve(String versionUid, ApproveBudgetVersionRequest request);

    /** Reject a SUBMITTED version → REJECTED (FR-BUD-12, BR-BUD-13). */
    BudgetVersionDto reject(String versionUid, RejectBudgetVersionRequest request);
}
