package com.erp.modules.iam.repository;

import com.erp.modules.iam.domain.entity.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByUid(String uid);

    Optional<Role> findByCode(String code);

    boolean existsByCode(String code);

    List<Role> findAllByOrderByName();

    /** Fetch a role with its permissions eagerly (avoids a lazy-init when serialising to a DTO). */
    @EntityGraph(attributePaths = "permissions")
    Optional<Role> findWithPermissionsByUid(String uid);
}
