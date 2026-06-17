package com.erp.modules.fx.service;

import com.erp.modules.fx.domain.dto.BranchCurrencyDto;
import com.erp.modules.fx.domain.dto.CompanyCurrencyDto;
import com.erp.modules.fx.domain.dto.EnableCurrencyRequest;
import com.erp.modules.fx.domain.exception.CurrencyNotEnabledException;
import com.erp.platform.common.money.CurrencyCode;
import java.util.List;

/**
 * Enablement allow-list management for currencies at company and branch level (ADR-0039 D-7/D-8).
 *
 * <p>Invariants enforced:
 * <ul>
 *   <li>Company base currency MUST be present, active, and cannot be deactivated.
 *   <li>The {@code is_default} row MUST be active.
 *   <li>A branch currency MUST be enabled+active in {@code company_currency} first (subset rule).
 *   <li>A code MUST be globally {@code currencies.active} to be enabled.
 * </ul>
 */
public interface CurrencyEnablementService {

    // ── UID resolution (keep repositories out of controllers) ─────────────────

    /**
     * Resolve a company uid to its numeric id.
     *
     * @throws com.erp.platform.common.api.NotFoundException if the uid is unknown
     */
    Long resolveCompanyId(String companyUid);

    /**
     * Resolve a branch uid to its numeric id, asserting the branch belongs to the given company.
     *
     * @throws com.erp.platform.common.api.NotFoundException if the uid is unknown or company mismatch
     */
    Long resolveBranchId(String branchUid, Long companyId);

    // ── Query ─────────────────────────────────────────────────────────────────

    /**
     * All active (enabled) currencies for the company.
     * Callers must have {@code assertCanActIn} resolved before calling.
     */
    List<CompanyCurrencyDto> enabledForCompany(Long companyId);

    /**
     * Effective enabled list for the branch: branch's own subset if any rows exist,
     * otherwise falls back to the company list (ADR-0039 D-7 inheritance).
     */
    List<BranchCurrencyDto> enabledForBranch(Long companyId, Long branchId);

    /**
     * Resolve the default document currency for the given scope (ADR-0039 D-7):
     * branch default → company default → company base currency.
     *
     * @return the resolved {@link CurrencyCode}; never null
     */
    CurrencyCode resolveDefault(Long companyId, Long branchId);

    /**
     * Assert the given currency is enabled for the scope. No-op if enabled.
     *
     * @throws CurrencyNotEnabledException if not enabled
     */
    void assertEnabled(Long companyId, Long branchId, CurrencyCode currencyCode);

    // ── Company-level management ──────────────────────────────────────────────

    /**
     * Enable (or re-enable) a currency for the company. If the row already exists it is activated.
     * If {@link EnableCurrencyRequest#setAsDefault()} is true, the default is moved to this row.
     *
     * <p>Invariant: the currency MUST be globally active in the {@code currencies} master.
     */
    CompanyCurrencyDto enableForCompany(Long companyId, EnableCurrencyRequest request);

    /**
     * Deactivate (soft-disable) a currency for the company.
     *
     * <p>Invariants: cannot deactivate the company base currency; the {@code is_default} row
     * cannot be deactivated unless another active row is set as default first.
     */
    CompanyCurrencyDto deactivateForCompany(Long companyId, String currencyCode);

    /**
     * Set the default document currency for the company.
     *
     * <p>Invariant: the target row MUST be active.
     */
    CompanyCurrencyDto setDefaultForCompany(Long companyId, String currencyCode);

    // ── Branch-level management ───────────────────────────────────────────────

    /**
     * Enable (or re-enable) a currency for the branch.
     *
     * <p>Invariant: the currency MUST be enabled+active in {@code company_currency}
     * for the same company (subset rule).
     */
    BranchCurrencyDto enableForBranch(Long companyId, Long branchId, EnableCurrencyRequest request);

    /**
     * Deactivate a currency for the branch.
     *
     * <p>Invariant: the {@code is_default} branch row cannot be deactivated unless another
     * active branch row is set as default first.
     */
    BranchCurrencyDto deactivateForBranch(Long companyId, Long branchId, String currencyCode);

    /**
     * Set the default document currency for the branch.
     *
     * <p>Invariant: the target row MUST be active and must exist in this branch's list.
     */
    BranchCurrencyDto setDefaultForBranch(Long companyId, Long branchId, String currencyCode);
}
