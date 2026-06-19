package com.erp.api;

import com.erp.modules.fx.domain.dto.BranchCurrencyDto;
import com.erp.modules.fx.domain.dto.CompanyCurrencyDto;
import com.erp.modules.fx.domain.dto.EnableCurrencyRequest;
import com.erp.modules.fx.domain.dto.EnabledCurrenciesDto;
import com.erp.modules.fx.service.CurrencyEnablementService;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Currency enablement allow-list endpoints (ADR-0039 D-7/D-8).
 *
 * <p>Permissions:
 * <ul>
 *   <li>{@code CURRENCY.VIEW} — read the enabled list / resolved default.
 *   <li>{@code CURRENCY.MANAGE} — enable, deactivate, set-default at company and branch level.
 * </ul>
 *
 * <p>Every response is auto-wrapped in {@code ApiResponse<T>} by the response-advice.
 * {@code assertCanActIn} is called on every path so no tenant read bypasses the scope guard.
 * Uid-to-id resolution is delegated to {@link CurrencyEnablementService} so this controller
 * holds no repository references (ModuleBoundaryTest).
 */
@RestController
@RequestMapping("/api/v1/fx")
public class CurrencyEnablementController {

    private final CurrencyEnablementService enablement;
    private final ScopeGuard               scopeGuard;

    public CurrencyEnablementController(CurrencyEnablementService enablement,
                                         ScopeGuard               scopeGuard) {
        this.enablement = enablement;
        this.scopeGuard = scopeGuard;
    }

    // ── GET /currencies/enabled ───────────────────────────────────────────────

    /**
     * Returns the effective enabled currency list and the resolved default for the given scope.
     *
     * <p>If {@code branchUid} is omitted (or blank) the company-level list is returned.
     * If the branch has no own rows it inherits the company list — the response shape is identical.
     */
    @GetMapping("/currencies/enabled")
    // The currency picker on every money form needs the company's enabled list — any authenticated
    // user may read it (no CURRENCY.VIEW). assertCanActIn below still scopes it to a company the
    // caller can act in; CURRENCY.MANAGE gates the enable/deactivate/set-default writes.
    @PreAuthorize("isAuthenticated()")
    public EnabledCurrenciesDto getEnabled(
            @RequestParam String companyUid,
            @RequestParam(required = false) String branchUid) {

        Long companyId = enablement.resolveCompanyId(companyUid);
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        Long branchId = null;
        if (branchUid != null && !branchUid.isBlank()) {
            branchId = enablement.resolveBranchId(branchUid, companyId);
        }

        List<String> enabledCodes;
        if (branchId != null) {
            enabledCodes = enablement.enabledForBranch(companyId, branchId)
                    .stream().map(BranchCurrencyDto::currencyCode).toList();
        } else {
            enabledCodes = enablement.enabledForCompany(companyId)
                    .stream().map(CompanyCurrencyDto::currencyCode).toList();
        }

        CurrencyCode resolved = enablement.resolveDefault(companyId, branchId);
        return new EnabledCurrenciesDto(resolved.value(), enabledCodes);
    }

    // ── Company-level management ──────────────────────────────────────────────

    /** Enable (or re-enable) a currency for the company. */
    @PostMapping("/companies/{companyUid}/currencies/enable")
    @PreAuthorize("@perm.has('CURRENCY.MANAGE')")
    public CompanyCurrencyDto enableForCompany(
            @PathVariable String companyUid,
            @Valid @RequestBody EnableCurrencyRequest req) {

        Long companyId = enablement.resolveCompanyId(companyUid);
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return enablement.enableForCompany(companyId, req);
    }

    /** Deactivate a currency for the company. */
    @PostMapping("/companies/{companyUid}/currencies/{currencyCode}/deactivate")
    @PreAuthorize("@perm.has('CURRENCY.MANAGE')")
    public CompanyCurrencyDto deactivateForCompany(
            @PathVariable String companyUid,
            @PathVariable String currencyCode) {

        Long companyId = enablement.resolveCompanyId(companyUid);
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return enablement.deactivateForCompany(companyId, currencyCode);
    }

    /** Set the default document currency for the company. */
    @PostMapping("/companies/{companyUid}/currencies/{currencyCode}/set-default")
    @PreAuthorize("@perm.has('CURRENCY.MANAGE')")
    public CompanyCurrencyDto setDefaultForCompany(
            @PathVariable String companyUid,
            @PathVariable String currencyCode) {

        Long companyId = enablement.resolveCompanyId(companyUid);
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return enablement.setDefaultForCompany(companyId, currencyCode);
    }

    /** List all active company_currency rows for the company. */
    @GetMapping("/companies/{companyUid}/currencies")
    @PreAuthorize("@perm.has('CURRENCY.VIEW')")
    public List<CompanyCurrencyDto> listForCompany(@PathVariable String companyUid) {
        Long companyId = enablement.resolveCompanyId(companyUid);
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return enablement.enabledForCompany(companyId);
    }

    // ── Branch-level management ───────────────────────────────────────────────

    /** Enable (or re-enable) a currency for the branch. */
    @PostMapping("/companies/{companyUid}/branches/{branchUid}/currencies/enable")
    @PreAuthorize("@perm.has('CURRENCY.MANAGE')")
    public BranchCurrencyDto enableForBranch(
            @PathVariable String companyUid,
            @PathVariable String branchUid,
            @Valid @RequestBody EnableCurrencyRequest req) {

        Long companyId = enablement.resolveCompanyId(companyUid);
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Long branchId = enablement.resolveBranchId(branchUid, companyId);
        return enablement.enableForBranch(companyId, branchId, req);
    }

    /** Deactivate a currency for the branch. */
    @PostMapping("/companies/{companyUid}/branches/{branchUid}/currencies/{currencyCode}/deactivate")
    @PreAuthorize("@perm.has('CURRENCY.MANAGE')")
    public BranchCurrencyDto deactivateForBranch(
            @PathVariable String companyUid,
            @PathVariable String branchUid,
            @PathVariable String currencyCode) {

        Long companyId = enablement.resolveCompanyId(companyUid);
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Long branchId = enablement.resolveBranchId(branchUid, companyId);
        return enablement.deactivateForBranch(companyId, branchId, currencyCode);
    }

    /** Set the default document currency for the branch. */
    @PostMapping("/companies/{companyUid}/branches/{branchUid}/currencies/{currencyCode}/set-default")
    @PreAuthorize("@perm.has('CURRENCY.MANAGE')")
    public BranchCurrencyDto setDefaultForBranch(
            @PathVariable String companyUid,
            @PathVariable String branchUid,
            @PathVariable String currencyCode) {

        Long companyId = enablement.resolveCompanyId(companyUid);
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Long branchId = enablement.resolveBranchId(branchUid, companyId);
        return enablement.setDefaultForBranch(companyId, branchId, currencyCode);
    }

    /** List all active branch_currency rows for the branch. */
    @GetMapping("/companies/{companyUid}/branches/{branchUid}/currencies")
    @PreAuthorize("@perm.has('CURRENCY.VIEW')")
    public List<BranchCurrencyDto> listForBranch(
            @PathVariable String companyUid,
            @PathVariable String branchUid) {

        Long companyId = enablement.resolveCompanyId(companyUid);
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Long branchId = enablement.resolveBranchId(branchUid, companyId);
        return enablement.enabledForBranch(companyId, branchId);
    }
}
