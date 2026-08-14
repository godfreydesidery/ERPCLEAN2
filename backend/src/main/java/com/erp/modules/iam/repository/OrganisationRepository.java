package com.erp.modules.iam.repository;

import com.erp.modules.iam.domain.entity.Organisation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganisationRepository extends JpaRepository<Organisation, Long> {

    Optional<Organisation> findByUid(String uid);

    /** One organisation per deployment — true once bootstrap has run (Slice 2). */
    boolean existsBy();

    /**
     * The deployment's single organisation. Single-org-per-deployment is the product model
     * (DATA-MODEL §1.1); the id-ordered "first" is a deterministic backstop if more than one ever
     * exists, rather than a non-deterministic {@code findAll().get(0)}.
     */
    Optional<Organisation> findFirstByOrderByIdAsc();

    /**
     * Load the organisation whose id came from the CALLER'S OWN principal, never from request
     * input — a self-scope lookup. Named (not the inherited {@code findById}) to mirror
     * {@code CompanyRepository.findScopedById}: the confused-deputy gate in
     * {@code TenantScopingRulesTest} flags bare {@code findById} in services because it cannot tell
     * a self-scope read from an attacker-supplied one, and the naming is how that distinction is
     * declared in this codebase.
     */
    @org.springframework.data.jpa.repository.Query("SELECT o FROM Organisation o WHERE o.id = :id")
    Optional<Organisation> findScopedById(@org.springframework.data.repository.query.Param("id") Long id);
}
