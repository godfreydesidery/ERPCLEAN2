package com.erp.modules.iam.repository;

import com.erp.modules.iam.domain.entity.Branch;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchRepository extends JpaRepository<Branch, Long> {

    Optional<Branch> findByUid(String uid);

    /**
     * Find by uid with the {@code company} association fetched eagerly. Used where the company's
     * status/id is read OUTSIDE a Hibernate session — e.g. the branch-switch override in
     * {@code JwtRequestContextFilter} (not @Transactional), so {@code isUsableForSession()} and
     * {@code getCompany()} don't trip a LazyInitializationException.
     */
    @EntityGraph(attributePaths = "company")
    Optional<Branch> findWithCompanyByUid(String uid);

    List<Branch> findByCompanyIdOrderByName(Long companyId);

    boolean existsByCompanyIdAndCode(Long companyId, String code);

    /** The current default branch of a company, if one is set (BR-2). */
    Optional<Branch> findByCompanyIdAndIsDefaultTrue(Long companyId);

    /**
     * Company-scoped uid lookup. Returns empty when the branch does not exist OR belongs to a
     * different company — callers should throw NotFoundException (404) in the empty case to avoid
     * leaking cross-tenant existence. Used by ApprovalPolicyServiceImpl.resolveBranchId() to
     * prevent confused-deputy attacks where a caller supplies a foreign branch uid.
     */
    Optional<Branch> findByUidAndCompanyId(String uid, Long companyId);

    /**
     * Ownership check: true when a branch with the given PK exists AND its parent company has the
     * given id. Branch maps company via {@code @ManyToOne Company company}, so the derived-query
     * path uses the underscore escape {@code Company_Id} to traverse the association.
     * Used by fixed-asset services to prevent cross-tenant branch binding on register /
     * acquireFromBill / transfer (tenant-isolation site 2 &amp; 3).
     */
    boolean existsByIdAndCompany_Id(Long id, Long companyId);

    /**
     * Company-scoped PK lookup. Tenant-safe finder for a service resolving a branch by the id
     * carried on an already-scoped entity (e.g. {@code order.getBranchId()}) — avoids a bare
     * {@code findById}, which {@code TenantScopingRulesTest} flags as confused-deputy-prone. Uses
     * the {@code Company_Id} underscore escape to traverse the {@code @ManyToOne Company}.
     */
    Optional<Branch> findByIdAndCompany_Id(Long id, Long companyId);
}
