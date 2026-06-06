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
}
