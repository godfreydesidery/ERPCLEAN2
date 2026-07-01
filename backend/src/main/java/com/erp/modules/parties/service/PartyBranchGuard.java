package com.erp.modules.parties.service;

import com.erp.modules.iam.repository.BranchRepository;
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

    public PartyBranchGuard(BranchRepository branches) {
        this.branches = branches;
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
        var branch = branches.findById(branchId)
                .orElseThrow(() -> new NotFoundException("Branch not found."));
        Long branchCompanyId = branch.getCompany().getId();
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
