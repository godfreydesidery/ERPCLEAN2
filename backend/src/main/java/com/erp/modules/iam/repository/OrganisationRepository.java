package com.erp.modules.iam.repository;

import com.erp.modules.iam.domain.entity.Organisation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganisationRepository extends JpaRepository<Organisation, Long> {

    Optional<Organisation> findByUid(String uid);

    /** One organisation per deployment — true once bootstrap has run (Slice 2). */
    boolean existsBy();
}
