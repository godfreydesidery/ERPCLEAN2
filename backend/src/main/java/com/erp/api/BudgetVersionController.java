package com.erp.api;

import com.erp.modules.budgeting.domain.dto.ApproveBudgetVersionRequest;
import com.erp.modules.budgeting.domain.dto.BudgetVersionDto;
import com.erp.modules.budgeting.domain.dto.RejectBudgetVersionRequest;
import com.erp.modules.budgeting.domain.dto.UpsertBudgetLineRequest;
import com.erp.modules.budgeting.service.BudgetService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Budget version + lines lifecycle REST controller (ADR-0034 D-1 / API surface).
 *
 * <p>URL layout:
 * <ul>
 *   <li>{@code GET  /api/v1/budget-versions/uid/{uid}}         — get version + lines</li>
 *   <li>{@code PUT  /api/v1/budget-versions/uid/{uid}/lines}   — upsert lines (DRAFT only)</li>
 *   <li>{@code POST /api/v1/budget-versions/uid/{uid}/submit}  — DRAFT → SUBMITTED</li>
 *   <li>{@code POST /api/v1/budget-versions/uid/{uid}/recall}  — SUBMITTED → DRAFT</li>
 *   <li>{@code POST /api/v1/budget-versions/uid/{uid}/approve} — SUBMITTED → APPROVED</li>
 *   <li>{@code POST /api/v1/budget-versions/uid/{uid}/reject}  — SUBMITTED → REJECTED</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/budget-versions")
public class BudgetVersionController {

    private final BudgetService budgetService;

    public BudgetVersionController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'budgetversion','BUDGETING.BUDGET.VIEW')")
    public BudgetVersionDto get(@PathVariable String uid) {
        return budgetService.getVersionByUid(uid);
    }

    @PutMapping("/uid/{uid}/lines")
    @PreAuthorize("@perm.scoped(#uid,'budgetversion','BUDGETING.BUDGET.MANAGE')")
    public BudgetVersionDto upsertLines(@PathVariable String uid,
                                        @Valid @RequestBody UpsertBudgetLineRequest request) {
        return budgetService.upsertLines(uid, request);
    }

    @PostMapping("/uid/{uid}/submit")
    @PreAuthorize("@perm.scoped(#uid,'budgetversion','BUDGETING.BUDGET.SUBMIT')")
    public BudgetVersionDto submit(@PathVariable String uid) {
        return budgetService.submit(uid);
    }

    @PostMapping("/uid/{uid}/recall")
    @PreAuthorize("@perm.scoped(#uid,'budgetversion','BUDGETING.BUDGET.SUBMIT')")
    public BudgetVersionDto recall(@PathVariable String uid) {
        return budgetService.recall(uid);
    }

    @PostMapping("/uid/{uid}/approve")
    @PreAuthorize("@perm.scoped(#uid,'budgetversion','BUDGETING.BUDGET.APPROVE')")
    public BudgetVersionDto approve(@PathVariable String uid,
                                    @Valid @RequestBody(required = false)
                                    ApproveBudgetVersionRequest request) {
        return budgetService.approve(uid, request);
    }

    @PostMapping("/uid/{uid}/reject")
    @PreAuthorize("@perm.scoped(#uid,'budgetversion','BUDGETING.BUDGET.APPROVE')")
    public BudgetVersionDto reject(@PathVariable String uid,
                                   @Valid @RequestBody RejectBudgetVersionRequest request) {
        return budgetService.reject(uid, request);
    }
}
