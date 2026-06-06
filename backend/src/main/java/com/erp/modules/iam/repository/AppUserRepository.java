package com.erp.modules.iam.repository;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.platform.common.domain.MasterStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUid(String uid);

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    List<AppUser> findAllByOrderByUsername();

    /**
     * F9 (ADR-0004 D-8): single indexed PK + status check used by the filter to re-validate the
     * user is still active on every authenticated request.
     */
    boolean existsByIdAndStatus(Long id, MasterStatus status);
}
