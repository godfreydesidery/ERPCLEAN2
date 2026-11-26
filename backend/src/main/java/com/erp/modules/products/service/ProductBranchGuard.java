package com.erp.modules.products.service;

import com.erp.modules.iam.repository.BranchRepository;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import org.springframework.stereotype.Component;

/**
 * Enforces BR-PROD-09: the branch being associated with a product must belong to the same
 * company as the product (ADR-0007 D-4/D-9). SQL FKs cannot assert cross-row company equality,
 * so this guard is the application-layer enforcement — modelled after {@code PartyBranchGuard}.
 *
 * <p>Note on module boundary: reads {@code BranchRepository} from the IAM module — the same
 * cross-cutting dependency pattern used by {@code PartyBranchGuard} and {@code ScopeGuard}.
 * This is an explicitly documented cross-module read for a specific guard purpose.
 */
@Component
public class ProductBranchGuard {

    private final BranchRepository branches;

    public ProductBranchGuard(BranchRepository branches) {
        this.branches = branches;
    }

    /**
     * Resolves the branch by uid, asserts it belongs to the product's company, and returns the
     * branch's internal id.
     *
     * @param productCompanyId the company the product belongs to
     * @param branchUid        the branch uid being associated
     * @return the branch's internal id
     * @throws NotFoundException  if the branch does not exist
     * @throws ForbiddenException if the branch belongs to a different company (BR-PROD-09)
     */
    public Long resolveAndAssertSameCompany(Long productCompanyId, String branchUid) {
        var branch = branches.findByUid(branchUid)
                .orElseThrow(() -> new NotFoundException("Branch not found."));
        Long branchCompanyId = branch.getCompany().getId();
        if (!branchCompanyId.equals(productCompanyId)) {
            // BR-PROD-09: cross-company branch assignment
            throw new ForbiddenException(
                    "The selected branch does not belong to the same company as the product.");
        }
        return branch.getId();
    }

    /** A branch as the products module needs to name it (no IAM entity leaves this guard). */
    public record BranchRef(Long id, String uid, String code, String name) {
    }

    /**
     * The ACTIVE branches of {@code companyId}, by name — the branch columns of the branch-menu
     * sheet. Scoped by the company the caller already resolved, never by a request value.
     */
    public java.util.List<BranchRef> activeBranchesOf(Long companyId) {
        return branches.findByCompanyIdOrderByName(companyId).stream()
                .filter(b -> b.getStatus() == com.erp.platform.common.domain.MasterStatus.ACTIVE)
                .map(b -> new BranchRef(b.getId(), b.getUid(), b.getCode(), b.getName()))
                .toList();
    }

    /** Ids of every branch of {@code companyId} (ADR-0073: materialising "sold everywhere"). */
    public java.util.List<Long> branchIdsOf(Long companyId) {
        return branches.findByCompanyIdOrderByName(companyId).stream()
                .map(com.erp.modules.iam.domain.entity.Branch::getId)
                .toList();
    }
}
