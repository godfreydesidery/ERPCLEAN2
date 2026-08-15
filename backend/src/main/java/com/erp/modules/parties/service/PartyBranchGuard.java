package com.erp.modules.parties.service;

import com.erp.modules.iam.repository.BranchRepository;
import com.erp.platform.security.CompanyTenantIndex;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.TenancyScopeEnforcer;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import org.springframework.stereotype.Component;

/**
 * Enforces BR-PARTY-01: the branch being associated with a party must belong to the same company
 * as the party (ADR-0006 D-4). SQL FKs cannot assert cross-row company equality, so this guard
 * is the application-layer enforcement — modelled after {@code ScopeGuard}.
 *
 * <p>Note on module boundary: this component reads {@code BranchRepository} from the IAM module.
 * This is the <em>same</em> cross-cutting dependency that {@code ScopeGuard} and
 * {@code PermissionResolver} already use (both are in {@code platform.security} and import IAM
 * repositories). {@code PartyBranchGuard} is in {@code modules.parties.service} and does the
 * same — it is an explicitly documented cross-module read for a specific guard purpose
 * (ADR-0006 D-1, D-4). The branch repository is read-only here; no IAM entity is imported
 * into the parties domain. {@code ModuleBoundaryTest} allows this pattern for service classes
 * that carry a declared enforcement role, consistent with the ArchUnit allowEmptyShould rule.
 */
@Component
public class PartyBranchGuard {

    private final BranchRepository branches;
    private final TenancyScopeEnforcer tenancy;
    private final CompanyTenantIndex companyTenants;

    public PartyBranchGuard(BranchRepository branches, TenancyScopeEnforcer tenancy,
                            CompanyTenantIndex companyTenants) {
        this.branches = branches;
        this.tenancy = tenancy;
        this.companyTenants = companyTenants;
    }

    /**
     * Asserts that the branch identified by {@code branchId} belongs to {@code partyCompanyId}.
     *
     * @param partyCompanyId the company the party belongs to
     * @param branchId       the branch being associated
     * @throws NotFoundException  if the branch does not exist
     * @throws ForbiddenException if the branch belongs to a different company (BR-PARTY-01)
     */
    public void assertSameCompany(Long partyCompanyId, Long branchId) {
        var branch = branches.findById(branchId).orElse(null);

        // Found in the P3-12 freeze-store triage: splitting "no such branch" from "belongs to another
        // company" tells the caller that a branch id exists somewhere in the estate. Harmless while
        // the estate was one customer; an existence oracle once it is not - and branchId here is a
        // sequential NUMBER, the one identifier in this codebase an outsider could actually guess
        // (everything caller-facing is addressed by ULID uid).
        //
        // Collapsed onto not-found ONLY when the branch is another TENANT's. A branch of a sibling
        // company inside the caller's own organisation keeps the explicit message: that is a
        // legitimate mistake by someone who owns both companies, they are entitled to know which
        // rule they broke, and there is no boundary to leak across. Flattening that case too would
        // have traded a real error message for no security at all.
        if (branch == null) {
            throw new NotFoundException("Branch not found.");
        }
        Long branchCompanyId = branch.getCompany().getId();
        if (tenancy.isForeignTenant(RequestContext.get(),
                companyTenants.organisationOf(branchCompanyId))) {
            throw new NotFoundException("Branch not found.");
        }
        if (!branchCompanyId.equals(partyCompanyId)) {
            // BR-PARTY-01: branch must belong to the same company as the party
            throw new ForbiddenException(
                    "The selected branch does not belong to the same company as this party.");
        }
    }

    /**
     * Asserts that the branch identified by its uid belongs to {@code partyCompanyId}.
     *
     * @param partyCompanyId the company the party belongs to
     * @param branchUid      the branch uid being associated
     * @throws NotFoundException  if the branch does not exist
     * @throws ForbiddenException if the branch belongs to a different company (BR-PARTY-01)
     */
    public void assertSameCompanyByUid(Long partyCompanyId, String branchUid) {
        var branch = branches.findByUid(branchUid)
                .orElseThrow(() -> new NotFoundException("Branch not found."));
        Long branchCompanyId = branch.getCompany().getId();
        if (!branchCompanyId.equals(partyCompanyId)) {
            // BR-PARTY-01: branch must belong to the same company as the party
            throw new ForbiddenException(
                    "The selected branch does not belong to the same company as this party.");
        }
    }

    /**
     * Returns the branch id for the given uid.
     *
     * @param branchUid the branch uid
     * @return the branch's internal id
     * @throws NotFoundException if the branch does not exist
     */
    public Long resolveAndAssertSameCompany(Long partyCompanyId, String branchUid) {
        var branch = branches.findByUid(branchUid)
                .orElseThrow(() -> new NotFoundException("Branch not found."));
        Long branchCompanyId = branch.getCompany().getId();
        if (!branchCompanyId.equals(partyCompanyId)) {
            // BR-PARTY-01: branch must belong to the same company as the party
            throw new ForbiddenException(
                    "The selected branch does not belong to the same company as this party.");
        }
        return branch.getId();
    }
}
