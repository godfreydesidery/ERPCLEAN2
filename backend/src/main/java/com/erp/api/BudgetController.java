package com.erp.api;

import com.erp.modules.budgeting.domain.dto.BudgetDto;
import com.erp.modules.budgeting.domain.dto.BudgetVersionDto;
import com.erp.modules.budgeting.domain.dto.CreateBudgetRequest;
import com.erp.modules.budgeting.domain.dto.CreateBudgetVersionRequest;
import com.erp.modules.budgeting.domain.enums.BudgetVersionStatus;
import com.erp.modules.budgeting.service.BudgetService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Budget header REST controller (ADR-0034 D-1 / API surface).
 *
 * <p>All responses auto-wrapped in {@code ApiResponse<T>} by {@code ApiResponseAdvice}.
 * Permission gates: {@code @perm.has} / {@code @perm.scoped} — NEVER {@code hasAuthority}.
 * {@code ScopeGuard.assertCanActIn} enforced on every read path in the service (NFR-BUD-01).
 *
 * <p>URL layout:
 * <ul>
 *   <li>{@code GET  /api/v1/budgets}                       — list budgets (paged)</li>
 *   <li>{@code GET  /api/v1/budgets/uid/{uid}}             — get budget detail + versions</li>
 *   <li>{@code POST /api/v1/budgets}                       — create budget + v1 DRAFT</li>
 *   <li>{@code POST /api/v1/budgets/uid/{uid}/versions}    — create new version (re-plan)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/budgets")
public class BudgetController {

    private final BudgetService budgetService;
    private final ScopeGuard scopeGuard;

    public BudgetController(BudgetService budgetService, ScopeGuard scopeGuard) {
        this.budgetService = budgetService;
        this.scopeGuard    = scopeGuard;
    }

    @GetMapping
    @PreAuthorize("@perm.has('BUDGETING.BUDGET.VIEW')")
    public ApiResponse<List<BudgetDto>> list(
            @RequestParam Long companyId,
            @RequestParam(required = false) String fiscalYearUid,
            @RequestParam(required = false) String costCentreValueUid,
            @RequestParam(required = false) BudgetVersionStatus versionStatus,
            Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Page<BudgetDto> page = budgetService.listBudgets(
                companyId, fiscalYearUid, costCentreValueUid, versionStatus, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'budget','BUDGETING.BUDGET.VIEW')")
    public BudgetDto get(@PathVariable String uid) {
        return budgetService.getBudgetByUid(uid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('BUDGETING.BUDGET.MANAGE')")
    public BudgetDto create(@Valid @RequestBody CreateBudgetRequest request) {
        return budgetService.createBudget(request);
    }

    @PostMapping("/uid/{uid}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'budget','BUDGETING.BUDGET.MANAGE')")
    public BudgetVersionDto createVersion(@PathVariable String uid,
                                          @Valid @RequestBody(required = false)
                                          CreateBudgetVersionRequest request) {
        return budgetService.createVersion(uid,
                request != null ? request : new CreateBudgetVersionRequest(null, null));
    }
}
