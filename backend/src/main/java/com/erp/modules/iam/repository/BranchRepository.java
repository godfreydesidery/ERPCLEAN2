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
}
